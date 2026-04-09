# Dependency Resolver - Architecture & Flow Documentation

## Overview

The Dependency Resolver automatically keeps Maven dependency versions up to date across repositories. It works in two stages:

1. **Registration** - A Maven plugin runs during `mvn deploy` on each library. It pushes the pom.xml to the registry (for any target branch) and updates the version config (from the trigger branch only).
2. **Resolution** - A Jenkins cron job runs every 10 minutes, reads the registry, diffs versions per branch, and creates PRs for outdated dependencies.

No webhooks, no Nexus, no manually maintained repo lists. Libraries self-register.

The registry (versions.yaml + pom copies) lives in the `registry/` folder **inside this same repo**.

---

## System Flow Diagram

```
                        REGISTRATION FLOW (on mvn deploy)
                        =================================

  +-------------------+       +---------------------------+
  | Library Build     |       | version-register-maven-   |
  | (mvn deploy)      |------>| plugin (RegisterVersionMojo)|
  +-------------------+       +---------------------------+
                                        |
                                        | 1. Detect current branch
                                        | 2. Parse targetBranches list
                                        |
                                        v
                        +-------------------------------+
                        | Is current branch in          |
                        | targetBranches OR             |
                        | triggerBranch?                 |
                        +-------------------------------+
                           |                    |
                          NO                   YES
                           |                    |
                        [SKIP]                  v
                              +--------------------------------+
                              | Is it a target branch?         |
                              | (develop, master, etc.)        |
                              +--------------------------------+
                                        |
                                       YES
                                        |
                                        v
                              +--------------------------------+
                              | Push pom.xml to                |
                              | registry/poms/{gId}/{aId}/     |
                              |   {branch}/pom.xml             |
                              +--------------------------------+
                                        |
                                        v
                              +--------------------------------+
                              | Is it also the trigger branch? |
                              | (master by default)            |
                              +--------------------------------+
                                   |              |
                                  NO             YES
                                   |              |
                                [done]            v
                              +--------------------------------+
                              | Update versions.yaml           |
                              | (upsert latestVersion,         |
                              |  repo coords, targetBranches)  |
                              | Retry on 409 conflict          |
                              +--------------------------------+
                                        |
                                        v
                        +-------------------------------+
                        | registry/ folder              |
                        | (inside THIS repo)            |
                        |                               |
                        | registry/versions.yaml        |
                        | registry/poms/                |
                        |   com.myorg/                  |
                        |     my-lib/                   |
                        |       master/pom.xml          |
                        |       develop/pom.xml         |
                        |     core-utils/               |
                        |       master/pom.xml          |
                        +-------------------------------+


                        RESOLUTION FLOW (Jenkins cron, every 10 min)
                        =============================================

  +-------------------+       +----------------------------+
  | Jenkins Cron      |       | CronResolverMain           |
  | (H/10 * * * *)   |------>| (java -jar resolver-core.jar)|
  +-------------------+       +----------------------------+
                                        |
                                        v
                              +--------------------+
                              | 1. Read versions.yaml|
                              |    from registry    |
                              +--------------------+
                                        |
                                        v
                              +--------------------+
                              | 2. Build lookup map|
                              |    groupId:artifactId |
                              |    -> latestVersion   |
                              +--------------------+
                                        |
                                        v
                        +-------------------------------+
                        | 3. FOR EACH artifact:         |
                        |    FOR EACH targetBranch:     |
                        +-------------------------------+
                                        |
                                        v
                              +--------------------+
                              | Read pom.xml from  |
                              | registry/poms/     |
                              | {gId}/{aId}/       |
                              | {branch}/pom.xml   |
                              +--------------------+
                                        |
                                        v
                              +--------------------+
                              | Parse pom with     |
                              | PomParser          |
                              | (DOM + XXE protect)|
                              +--------------------+
                                        |
                                        v
                        +-------------------------------+
                        | FOR EACH dependency in pom:   |
                        |   Is it in the registry?      |
                        |   Is its version outdated?    |
                        +-------------------------------+
                                   |          |
                                  NO         YES
                                   |          |
                                [skip]        v
                              +--------------------+
                              | PomModifier:       |
                              | Update version in  |
                              | pom (regex-based,  |
                              | preserves format)  |
                              +--------------------+
                                        |
                                        v
                              +--------------------+
                              | Collect all bumps  |
                              | into single PR     |
                              | (batched updates)  |
                              +--------------------+
                                        |
                                        v
                        +-------------------------------+
                        | PullRequestCreator:           |
                        |  Branch: deps/{target}/       |
                        |    bump-{name}-to-{ver}       |
                        |  1. Check branch exists?      |
                        |  2. Check PR exists?          |
                        |  3. Create branch from HEAD   |
                        |  4. Commit updated pom.xml    |
                        |  5. Open PR targeting         |
                        |     {targetBranch}            |
                        +-------------------------------+
                                        |
                                        v
                              +--------------------+
                              | PR created on the  |
                              | library's own repo |
                              | targeting the      |
                              | specific branch    |
                              +--------------------+
```

---

## Detailed Component Documentation

### 1. Maven Plugin (`RegisterVersionMojo`)

**When it runs:** During `mvn deploy` phase.

**What it does:**

```
execute()
  |
  +-- Detect current branch:
  |     1. Check env vars: GIT_BRANCH, BRANCH_NAME, GITHUB_REF_NAME, CI_COMMIT_BRANCH
  |     2. Strip "origin/" prefix (Jenkins)
  |     3. Fallback: git rev-parse --abbrev-ref HEAD
  |     4. If undetectable: allow (conservative)
  |
  +-- Parse targetBranches (comma-separated -> list)
  |
  +-- Is current branch a target branch OR the trigger branch?
  |     NO  --> skip entirely
  |     YES --> continue
  |
  +-- If target branch: push pom.xml
  |     Path: registry/poms/{groupId}/{artifactId}/{branch}/pom.xml
  |
  +-- If trigger branch: update versions.yaml
        - Upsert entry with latestVersion, repo coords, targetBranches
        - Retry on 409 conflict (up to 3 times, exponential backoff)
```

**Plugin configuration:**

```xml
<plugin>
  <groupId>com.depresolver</groupId>
  <artifactId>version-register-maven-plugin</artifactId>
  <version>1.0.0</version>
  <executions>
    <execution>
      <phase>deploy</phase>
      <goals><goal>register</goal></goals>
    </execution>
  </executions>
  <configuration>
    <repoOwner>myorg</repoOwner>
    <repoName>my-lib</repoName>
    <targetBranches>master,develop</targetBranches>
    <githubToken>${env.GITHUB_TOKEN}</githubToken>
  </configuration>
</plugin>
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `repoOwner` | (required) | GitHub owner of your library's repo |
| `repoName` | (required) | GitHub repo name |
| `githubToken` | (required) | GitHub personal access token |
| `targetBranches` | `master` | Comma-separated branches to receive update PRs |
| `triggerBranch` | `master` | Branch whose deploy updates versions.yaml |
| `pomPath` | `pom.xml` | Path to pom.xml in your repo |
| `registryBranch` | `main` | Branch of the registry repo (this repo) |

The registry location (`myorg/dependency-resolver-cli`) is hardcoded in `RegistryClient`.

---

### 2. Registry Format

The registry lives in the `registry/` folder of **this repo**.

**registry/versions.yaml:**

```yaml
artifacts:
  - groupId: com.myorg
    artifactId: my-lib
    latestVersion: 2.1.0
    repoOwner: myorg
    repoName: my-lib
    targetBranches:
      - master
      - develop
    pomPath: pom.xml
    updatedAt: "2026-04-08T10:30:00Z"
```

**POMs stored per branch:**

```
registry/poms/
  com.myorg/
    my-lib/
      master/pom.xml
      develop/pom.xml
    core-utils/
      master/pom.xml
```

---

### 3. Cron Resolver (`CronResolverMain`)

**When it runs:** Jenkins cron, every 10 minutes.

**Algorithm:**

```
1. READ versions.yaml from registry
     -> All registered artifacts with latest versions + target branches

2. BUILD lookup map:
     "com.myorg:my-lib"     -> "2.1.0"
     "com.myorg:core-utils" -> "1.5.3"

3. FOR EACH artifact in registry:
     FOR EACH branch in artifact.targetBranches:
       a. READ pom from registry/poms/{groupId}/{artifactId}/{branch}/pom.xml
       b. PARSE with PomParser -> extract dependencies + properties
       c. FOR EACH dependency (direct + managed):
            - Is groupId:artifactId in our lookup map?
            - Is the version in pom != latestVersion?
            - If YES: apply PomModifier to update version
       d. IF any bumps collected:
            - Create PR targeting that branch
            - Branch name: deps/{targetBranch}/bump-{name}-to-{ver}
            - Idempotency: skip if branch or PR already exists
```

**Example scenario:**

```
Registry:
  com.pool:pool -> latestVersion: 2.0.0

service-b (targetBranches: [master, develop]):
  master/pom.xml has pool 1.5.0    --> PR to bump to 2.0.0 on master
  develop/pom.xml has pool 1.8.0   --> PR to bump to 2.0.0 on develop
```

---

### 4. POM Parsing & Modification

**PomParser** handles three version styles:

| Style | Example | How it's detected |
|-------|---------|-------------------|
| DIRECT | `<version>1.0.0</version>` | Literal version string |
| PROPERTY | `<version>${pool.version}</version>` | Matches `\$\{(.+?)}` pattern |
| MANAGED | Version in `<dependencyManagement>` | Found under `<dependencyManagement><dependencies>` |

**PomModifier** updates versions using regex (never DOM serialization):

- **Property versions:** Finds `<pool.version>1.0.0</pool.version>` and replaces the value
- **Direct/Managed versions:** Finds the `<dependency>` block matching groupId + artifactId, then replaces `<version>` within that block only

Preserves formatting, comments, whitespace, and XML structure.

---

### 5. Conflict Handling

**Problem:** Two library builds may try to update `versions.yaml` at the same time.

**Solution:** GitHub Contents API uses SHA-based optimistic locking.

```
Attempt 1: Read versions.yaml (sha=abc123)
           Modify in memory
           Write back with sha=abc123
           -> If sha still matches: SUCCESS
           -> If another write happened: 409 CONFLICT

Attempt 2: Re-read versions.yaml (sha=def456)
           Modify in memory
           Write back with sha=def456
           -> Usually succeeds

Max 3 attempts with 500ms * attempt backoff.
```

---

### 6. Idempotency

The cron resolver is safe to run repeatedly:

| Scenario | What happens |
|----------|-------------|
| PR already created for this version bump | Branch exists check -> skip |
| Dependency already up to date | Version equality check -> skip |
| Pom not yet in registry for a branch | readPom fails -> skip with warning |
| Registry empty | No artifacts to process -> exit 0 |

---

## Project Structure

```
dependency-resolver/
+-- pom.xml                                (parent POM, modules)
|
+-- resolver-core/                         (fat JAR for Jenkins)
|   +-- pom.xml
|   +-- src/main/java/com/depresolver/
|       +-- CronResolverMain.java          (CLI entry point)
|       +-- github/
|       |   +-- GitHubClient.java          (REST API client)
|       |   +-- GitHubConflictException.java
|       |   +-- PullRequestCreator.java    (PR orchestration)
|       +-- pom/
|       |   +-- PomParser.java             (DOM-based XML parsing)
|       |   +-- PomModifier.java           (regex-based XML editing)
|       |   +-- PropertyResolver.java      (${property} resolution)
|       +-- registry/
|       |   +-- ArtifactEntry.java         (registry entry model)
|       |   +-- VersionRegistry.java       (registry root model)
|       |   +-- RegistryClient.java        (read/write/upsert)
|       +-- scanner/
|           +-- DependencyMatch.java       (version match DTO)
|
+-- version-register-maven-plugin/         (Maven plugin for libraries)
|   +-- pom.xml
|   +-- src/main/java/com/depresolver/plugin/
|       +-- RegisterVersionMojo.java       (deploy-phase Mojo)
|
+-- registry/                              (version registry data)
|   +-- versions.yaml                      (artifact index)
|   +-- poms/                              (library pom.xml copies, per branch)
|
+-- jenkins-shared-lib/
    +-- Jenkinsfile                         (cron trigger)
```

---

## CLI Usage

```bash
java -jar resolver-core.jar \
  --github-token "$GITHUB_TOKEN" \
  --dry-run

# Options:
#   -t, --github-token     GitHub PAT (required)
#   --registry-branch      Registry branch (default: main)
#   --branch-prefix        PR branch prefix (default: deps)
#   --dry-run              Log only, don't create PRs
```

---

## Branch Logic

```
mvn deploy on develop (targetBranches=master,develop, triggerBranch=master):
  --> Push pom to registry/poms/{gId}/{aId}/develop/pom.xml
  --> Do NOT update versions.yaml

mvn deploy on master:
  --> Push pom to registry/poms/{gId}/{aId}/master/pom.xml
  --> Update versions.yaml with new release version

mvn deploy on feature-branch:
  --> Skip entirely (not a target or trigger branch)

Cron resolver:
  --> For each artifact, for each targetBranch:
      Read pom for that branch, diff against latest versions, create PR
      PR branch: deps/{targetBranch}/bump-{name}-to-{version}
```

---

## Safeguards

| Safeguard | Implementation |
|-----------|---------------|
| XXE protection | DOM parser disables external entities |
| Format preservation | Regex-based pom editing, never DOM serialization |
| Optimistic locking | GitHub SHA-based concurrency control with retry |
| Idempotent PRs | Branch + PR existence checks before creation |
| Branch-aware registration | Only trigger branch updates versions.yaml |
| Multi-branch pom tracking | Each target branch has its own pom in registry |
| Rate limit awareness | GitHubClient monitors X-RateLimit-Remaining |
| Error resilience | Per-artifact failures don't stop processing others |
| Dry run mode | Full flow without API mutations |
