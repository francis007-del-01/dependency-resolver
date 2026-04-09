# Dependency Resolver - Architecture & Flow Documentation

## Overview

The Dependency Resolver automatically keeps Maven dependency versions up to date across repositories. It works in two stages:

1. **Registration** - A Maven plugin runs during `mvn deploy` on each library. It pushes the pom.xml to the registry (for any target branch) and updates the version config (from the trigger branch only). Both are optional per library.
2. **Resolution** - A Jenkins cron job runs every 10 minutes, discovers all pom folders in the registry, diffs versions using semantic comparison, and creates PRs for outdated dependencies.

No webhooks, no Nexus, no manually maintained repo lists. Libraries self-register.

The registry (versions.yaml + pom copies) lives in the `registry/` folder **inside this same repo**.

---

## System Flow Diagram

```
                        REGISTRATION FLOW (on mvn deploy)
                        =================================

  +-------------------+       +---------------------------+
  | Library Build     |       | resolver-plugin            |
  | (mvn deploy)      |------>| (RegisterVersionMojo)      |
  +-------------------+       +---------------------------+
                                        |
                                        | currentBranch from CI
                                        |
                                        v
                        +-------------------------------+
                        | Is current branch in          |
                        | targetBranches OR             |
                        | triggerBranch?                 |
                        | (both optional per library)    |
                        +-------------------------------+
                           |                    |
                          NO                   YES
                           |                    |
                        [SKIP]                  v
                              +--------------------------------+
                              | Is it a target branch?         |
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
                              +--------------------------------+
                                   |              |
                                  NO             YES
                                   |              |
                                [done]            v
                              +--------------------------------+
                              | Update versions.yaml           |
                              | (upsert latestVersion,         |
                              |  repo coords)                  |
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
                              |    (latest versions)|
                              +--------------------+
                                        |
                                        v
                              +--------------------+
                              | 2. List all pom     |
                              |    folders in       |
                              |    registry/poms/   |
                              |    (discover        |
                              |     consumers)      |
                              +--------------------+
                                        |
                                        v
                        +-------------------------------+
                        | 3. FOR EACH consumer:         |
                        |    FOR EACH branch found:     |
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
                        |   Is it in versions.yaml?     |
                        |   Is version older? (semver)  |
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

**Module:** `resolver-plugin`

**When it runs:** During `mvn deploy` phase.

**What it does:**

```
execute()
  |
  +-- Receive currentBranch from CI (explicit param)
  |
  +-- Parse targetBranches (comma-separated -> list, optional)
  |
  +-- Is current branch a target branch OR the trigger branch?
  |     NO  --> skip entirely
  |     YES --> continue
  |
  +-- If target branch: push pom.xml via GitHub API
  |     Path: registry/poms/{groupId}/{artifactId}/{branch}/pom.xml
  |
  +-- If trigger branch: update versions.yaml via GitHub API
        - Upsert entry with latestVersion, repo coords
        - Retry on 409 conflict (up to 3 times, exponential backoff)
```

**Plugin configuration:**

```xml
<plugin>
  <groupId>com.depresolver</groupId>
  <artifactId>resolver-plugin</artifactId>
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
    <currentBranch>${env.BRANCH_NAME}</currentBranch>
    <triggerBranch>master</triggerBranch>
    <targetBranches>master,develop</targetBranches>
    <githubToken>${env.GITHUB_TOKEN}</githubToken>
  </configuration>
</plugin>
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `repoOwner` | (required) | GitHub owner of your library's repo |
| `repoName` | (required) | GitHub repo name |
| `currentBranch` | (required) | Current branch from CI |
| `githubToken` | (required) | GitHub personal access token |
| `targetBranches` | (optional) | Comma-separated branches to push poms for |
| `triggerBranch` | (optional) | Branch whose deploy updates versions.yaml |
| `pomPath` | `pom.xml` | Path to pom.xml in your repo |

The registry location (`myorg/dependency-resolver-cli`) is hardcoded in `RegistryClient`.

---

### 2. Registry Format

The registry lives in the `registry/` folder of **this repo**.

**registry/versions.yaml** (only trigger artifacts):

```yaml
artifacts:
  - groupId: com.myorg
    artifactId: my-lib
    latestVersion: 2.1.0
    repoOwner: myorg
    repoName: my-lib
    pomPath: pom.xml
    updatedAt: "2026-04-08T10:30:00Z"
```

**POMs stored per branch** (all target artifacts):

```
registry/poms/
  com.myorg/
    my-lib/
      master/pom.xml
      develop/pom.xml
    core-utils/
      master/pom.xml
```

Key distinction:
- `versions.yaml` contains artifacts that **publish** versions (trigger branch deployed)
- `poms/` contains artifacts that **receive** updates (target branch deployed)
- An artifact can be in both, one, or neither

---

### 3. Cron Resolver (`CronResolverMain`)

**Module:** `resolver-core`

**When it runs:** Jenkins cron, every 10 minutes.

**Algorithm:**

```
1. READ versions.yaml
     -> Build latestVersions map: groupId:artifactId -> version
     -> Build metadata map: groupId:artifactId -> ArtifactEntry (repoOwner, repoName, pomPath)

2. LIST all pom folders in registry/poms/ (discover consumers)
     -> List groupId dirs, then artifactId dirs under each

3. FOR EACH consumer (pom folder):
     a. Look up metadata from versions.yaml (need repoOwner/repoName for PR)
     b. LIST branch dirs under this artifact
     c. FOR EACH branch:
        - READ pom from registry
        - PARSE with PomParser
        - FOR EACH dependency: check if older than latestVersion (semver)
        - Apply PomModifier for each outdated dep
        - Create PR with all bumps batched
```

**Version comparison:** Uses `VersionComparator.isOlderThan()` — proper semantic versioning. No downgrades (2.0.0 -> 1.5.0 is skipped). SNAPSHOT is older than release (1.0.0-SNAPSHOT < 1.0.0).

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
| Dependency already up to date | Semver comparison -> skip |
| Dependency is newer than registry | Semver comparison -> skip (no downgrade) |
| Pom not yet in registry for a branch | readPom fails -> skip with warning |
| No metadata in versions.yaml | Skip PR (can't determine target repo) |
| Registry empty | No artifacts to process -> exit 0 |

---

## Project Structure

```
dependency-resolver/
+-- pom.xml                                (parent POM, 3 modules)
|
+-- resolver-common/                       (shared classes)
|   +-- github/GitHubClient.java           (GitHub REST API)
|   +-- github/GitHubConflictException.java
|   +-- registry/RegistryClient.java       (registry read/write/discover)
|   +-- registry/ArtifactEntry.java        (registry data model)
|   +-- registry/VersionRegistry.java      (registry root model)
|   +-- registry/VersionComparator.java    (semantic version comparison)
|
+-- resolver-core/                         (fat JAR for Jenkins cron)
|   +-- CronResolverMain.java             (CLI entry point)
|   +-- github/PullRequestCreator.java    (PR creation + idempotency)
|   +-- pom/PomParser.java               (DOM-based XML parsing)
|   +-- pom/PomModifier.java             (regex-based XML editing)
|   +-- pom/PropertyResolver.java        (${property} resolution)
|   +-- scanner/DependencyMatch.java     (version match DTO)
|
+-- resolver-plugin/                       (Maven plugin for libraries)
|   +-- plugin/RegisterVersionMojo.java   (deploy-phase Mojo)
|
+-- registry/                              (version registry data)
|   +-- versions.yaml                     (trigger artifact index)
|   +-- poms/                             (library pom.xml copies, per branch)
|
+-- jenkins-shared-lib/
    +-- Jenkinsfile                        (cron trigger)
```

---

## CLI Usage

```bash
java -jar resolver-core.jar \
  --github-token "$GITHUB_TOKEN" \
  --dry-run

# Options:
#   -t, --github-token     GitHub PAT (required)
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
  --> Read versions.yaml for latest versions
  --> List all pom folders in registry (discover consumers + branches)
  --> For each consumer, for each branch:
      Read pom, compare versions (semver, no downgrades), create PR
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
| No downgrades | Semantic version comparison (VersionComparator) |
| Branch-aware registration | Only trigger branch updates versions.yaml |
| Consumer discovery | Cron iterates pom folders, not versions.yaml entries |
| Rate limit awareness | GitHubClient monitors X-RateLimit-Remaining |
| Error resilience | Per-artifact failures don't stop processing others |
| Dry run mode | Full flow without API mutations |
