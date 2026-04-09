# Dependency Resolver

Automatically keeps Maven dependency versions up to date across repositories by creating pull requests.

## High-Level Design

```
+------------------+          +------------------------------------------+
|  Library Repos   |          |  This Repo (dependency-resolver-cli)     |
|                  |          |                                          |
|  +------------+  |  deploy  |  +------------------------------------+  |
|  | my-lib     |--+--------->|  | registry/                          |  |
|  +------------+  |          |  |   versions.yaml  (version index)   |  |
|                  |          |  |   poms/                             |  |
|  +------------+  |  deploy  |  |     com.myorg/my-lib/              |  |
|  | service-b  |--+--------->|  |       master/pom.xml               |  |
|  +------------+  |          |  |       develop/pom.xml              |  |
|                  |          |  |     com.myorg/service-b/            |  |
|  +------------+  |  deploy  |  |       master/pom.xml               |  |
|  | core-utils |--+--------->|  +------------------------------------+  |
|  +------------+  |          |                  |                       |
|       ^          |          |                  | Jenkins cron (10 min) |
|       |          |          |                  v                       |
|       |          |          |  +------------------------------------+  |
|       |          |          |  | CronResolverMain                   |  |
|       |          |          |  |                                    |  |
|       |          |          |  | 1. Read versions.yaml              |  |
|       |          |          |  | 2. For each lib, each branch:     |  |
|       |          |          |  |    - Read branch pom from registry |  |
|       |          |          |  |    - Parse dependencies            |  |
|       |          |          |  |    - Diff against latest versions  |  |
|       |          |          |  |    - If outdated: create PR        |  |
|       |          |          |  +------------------------------------+  |
|       |          |          |                  |                       |
+-------+----------+          +------------------+-----------------------+
        |                                        |
        +---- PRs with bumped pom.xml -----------+

+-----------------------------------------------------------------------+
|                        Key Components                                 |
|                                                                       |
|  Maven Plugin (RegisterVersionMojo)                                   |
|    Runs at deploy time in each library's CI                           |
|    - Target branch build  -->  push pom to registry                   |
|    - Trigger branch build -->  push pom + update versions.yaml        |
|                                                                       |
|  Registry (registry/ folder in this repo)                             |
|    - versions.yaml: index of all libs, latest versions, target branches|
|    - poms/: copy of each lib's pom.xml per branch                     |
|                                                                       |
|  Cron Resolver (CronResolverMain)                                     |
|    Fat JAR run by Jenkins every 10 min                                |
|    - Reads registry, diffs versions, creates PRs                      |
|    - One PR per lib per branch, batches multiple dep bumps            |
|                                                                       |
|  PomParser + PomModifier                                              |
|    - DOM-based parsing (XXE-safe) with property resolution            |
|    - Regex-based editing (preserves formatting)                       |
|                                                                       |
|  GitHubClient + PullRequestCreator                                    |
|    - GitHub REST API with rate-limit awareness                        |
|    - Optimistic locking (SHA-based) with retry on 409                 |
|    - Idempotent PR creation (branch/PR existence checks)              |
+-----------------------------------------------------------------------+
```

---

## How It Works

```
Library deploys (mvn deploy)
  |
  +-- Maven plugin detects branch
  |
  +-- Target branch (develop, master)?  -->  Push pom.xml to registry
  +-- Trigger branch (master)?          -->  Push pom.xml + update versions.yaml
  +-- Other branch?                     -->  Skip
  
Jenkins cron (every 10 min)
  |
  +-- Read versions.yaml (latest versions of all libs)
  +-- For each lib, for each target branch:
  |     Read that branch's pom.xml from registry
  |     Diff dependency versions against latest
  |     If outdated --> create PR on that branch
  +-- Done
```

Two concepts:
- **Trigger branch** (`master` by default) - the branch whose deploy updates `versions.yaml` with the new release version
- **Target branches** (`master` by default, configurable) - branches that receive dependency update PRs. Each target branch has its own pom stored in the registry.

## Quick Start

### 1. Add the plugin to your library's pom.xml

```xml
<plugin>
  <groupId>com.depresolver</groupId>
  <artifactId>version-register-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <executions>
    <execution>
      <phase>deploy</phase>
      <goals><goal>register</goal></goals>
    </execution>
  </executions>
  <configuration>
    <repoOwner>myorg</repoOwner>
    <repoName>my-lib</repoName>
    <githubToken>${env.GITHUB_TOKEN}</githubToken>
  </configuration>
</plugin>
```

That's it. On the next `mvn deploy` from master, your library is registered.

### 2. Multi-branch updates

To receive PRs on both `master` and `develop`:

```xml
<configuration>
  <repoOwner>myorg</repoOwner>
  <repoName>my-lib</repoName>
  <targetBranches>master,develop</targetBranches>
  <githubToken>${env.GITHUB_TOKEN}</githubToken>
</configuration>
```

Now deploys from `develop` push the develop pom, deploys from `master` push the master pom + update the version registry. The cron resolver creates PRs for both branches.

### 3. Run the cron resolver

```bash
java -jar resolver-core.jar --github-token "$GITHUB_TOKEN"
```

Or set up the Jenkins cron (see `jenkins-shared-lib/Jenkinsfile`).

## Plugin Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `repoOwner` | (required) | GitHub owner of your library's repo |
| `repoName` | (required) | GitHub repo name of your library |
| `githubToken` | (required) | GitHub personal access token |
| `targetBranches` | `master` | Comma-separated branches to receive update PRs |
| `triggerBranch` | `master` | Branch whose deploy updates versions.yaml |
| `pomPath` | `pom.xml` | Path to pom.xml in your repo |
| `registryBranch` | `main` | Branch of the registry repo (this repo) |

## Cron Resolver CLI

```
Usage: cron-resolver [-hV] [--dry-run] -t=<githubToken>
                     [--branch-prefix=<branchPrefix>]
                     [--registry-branch=<registryBranch>]

Options:
  -t, --github-token     GitHub PAT (required)
  --registry-branch      Registry branch (default: main)
  --branch-prefix        PR branch prefix (default: deps)
  --dry-run              Log only, don't create PRs
```

## Registry

The registry lives in the `registry/` folder of this repo:

```
registry/
  versions.yaml                          # All registered artifacts + latest versions
  poms/
    com.myorg/
      my-lib/
        master/pom.xml                   # pom from master branch
        develop/pom.xml                  # pom from develop branch
      core-utils/
        master/pom.xml
```

**versions.yaml** format:

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

## Project Structure

```
dependency-resolver/
  pom.xml                                  # Parent POM
  resolver-core/                           # Fat JAR (cron resolver)
    src/main/java/com/depresolver/
      CronResolverMain.java                # CLI entry point
      github/GitHubClient.java             # GitHub REST API
      github/PullRequestCreator.java       # PR creation + idempotency
      pom/PomParser.java                   # XML parsing (XXE-safe)
      pom/PomModifier.java                 # Regex-based version updates
      registry/RegistryClient.java         # Registry read/write
      registry/ArtifactEntry.java          # Registry data model
  version-register-maven-plugin/           # Maven plugin
    src/main/java/com/depresolver/plugin/
      RegisterVersionMojo.java             # Deploy-phase Mojo
  registry/                                # Version registry data
    versions.yaml
    poms/
  jenkins-shared-lib/
    Jenkinsfile                            # Cron trigger
```

## Branch Logic

```
mvn deploy on develop (targetBranches=master,develop, triggerBranch=master):
  --> Push pom to registry/poms/{gId}/{aId}/develop/pom.xml
  --> Do NOT update versions.yaml

mvn deploy on master:
  --> Push pom to registry/poms/{gId}/{aId}/master/pom.xml
  --> Update versions.yaml with new release version

mvn deploy on feature-branch:
  --> Skip entirely

Cron resolver:
  --> For each artifact, for each targetBranch:
      Read pom for that branch, diff against latest versions, create PR
```

## Build

```bash
# Build everything
mvn clean install

# Run tests
mvn clean test -pl resolver-core

# Package fat JAR
mvn clean package -pl resolver-core
# Output: resolver-core/target/resolver-core-1.0.0-SNAPSHOT.jar
```

Requires Java 21+.
