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
|       |          |          |  | 1. Read versions.yaml (latest ver) |  |
|       |          |          |  | 2. List all pom folders (consumers)|  |
|       |          |          |  | 3. For each consumer, each branch: |  |
|       |          |          |  |    - Read branch pom from registry |  |
|       |          |          |  |    - Parse dependencies            |  |
|       |          |          |  |    - Version compare (semver)      |  |
|       |          |          |  |    - If outdated: create PR        |  |
|       |          |          |  +------------------------------------+  |
|       |          |          |                  |                       |
+-------+----------+          +------------------+-----------------------+
        |                                        |
        +---- PRs with bumped pom.xml -----------+

+-----------------------------------------------------------------------+
|                        Key Components                                 |
|                                                                       |
|  Maven Plugin (RegisterVersionMojo)       [resolver-plugin]           |
|    Runs at deploy time in each library's CI                           |
|    - Target branch build  -->  push pom to registry                   |
|    - Trigger branch build -->  push pom + update versions.yaml        |
|    - Both are optional — configure per library                        |
|                                                                       |
|  Registry (registry/ folder in this repo)                             |
|    - versions.yaml: index of trigger artifacts + latest versions      |
|    - poms/: copy of each lib's pom.xml per branch (consumers)         |
|                                                                       |
|  Cron Resolver (CronResolverMain)         [resolver-core]             |
|    Fat JAR run by Jenkins every 10 min                                |
|    - Iterates pom folders (consumers), not versions.yaml entries      |
|    - Discovers branches from registry directory structure              |
|    - Semantic version comparison (no downgrades)                      |
|    - One PR per lib per branch, batches multiple dep bumps            |
|                                                                       |
|  Shared (GitHubClient, RegistryClient)    [resolver-common]           |
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
  +-- Plugin receives currentBranch from CI
  |
  +-- Target branch (develop, master)?  -->  Push pom.xml to registry
  +-- Trigger branch (master)?          -->  Push pom.xml + update versions.yaml
  +-- Neither configured?               -->  Skip
  
Jenkins cron (every 10 min)
  |
  +-- Read versions.yaml (latest versions from trigger branches)
  +-- List all pom folders in registry (discover consumers)
  +-- For each consumer, for each branch:
  |     Read that branch's pom.xml from registry
  |     Parse dependencies, compare versions (semver)
  |     If older --> create PR on that branch
  +-- Done
```

Two concepts:
- **Trigger branch** — the branch whose deploy updates `versions.yaml` with the new release version. Optional per library.
- **Target branches** — branches whose poms get pushed to the registry. The cron resolver discovers these from the directory structure and creates PRs for each. Optional per library.

## Quick Start

### 1. Add the plugin to your library's pom.xml

```xml
<plugin>
  <groupId>com.depresolver</groupId>
  <artifactId>resolver-plugin</artifactId>
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
    <currentBranch>${env.BRANCH_NAME}</currentBranch>
    <triggerBranch>master</triggerBranch>
    <targetBranches>master,develop</targetBranches>
    <githubToken>${env.GITHUB_TOKEN}</githubToken>
  </configuration>
</plugin>
```

### 2. Configuration examples

```xml
<!-- Library that only publishes versions (trigger only) -->
<configuration>
    <repoOwner>myorg</repoOwner>
    <repoName>core-lib</repoName>
    <currentBranch>${env.BRANCH_NAME}</currentBranch>
    <triggerBranch>master</triggerBranch>
    <githubToken>${env.GITHUB_TOKEN}</githubToken>
</configuration>

<!-- Service that only receives updates (target only) -->
<configuration>
    <repoOwner>myorg</repoOwner>
    <repoName>my-service</repoName>
    <currentBranch>${env.BRANCH_NAME}</currentBranch>
    <targetBranches>master,develop</targetBranches>
    <githubToken>${env.GITHUB_TOKEN}</githubToken>
</configuration>

<!-- Library that does both -->
<configuration>
    <repoOwner>myorg</repoOwner>
    <repoName>shared-lib</repoName>
    <currentBranch>${env.BRANCH_NAME}</currentBranch>
    <triggerBranch>master</triggerBranch>
    <targetBranches>master,develop</targetBranches>
    <githubToken>${env.GITHUB_TOKEN}</githubToken>
</configuration>
```

### 3. Run the cron resolver

```bash
GITHUB_TOKEN=your_token java -jar resolver-core.jar
```

Or set up the Jenkins cron (see `jenkins-shared-lib/Jenkinsfile`). The app reads config from `application.yml` and environment variables.

## Plugin Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `repoOwner` | (required) | GitHub owner of your library's repo |
| `repoName` | (required) | GitHub repo name of your library |
| `currentBranch` | (required) | Current branch from CI (e.g. `${env.BRANCH_NAME}`) |
| `githubToken` | (required) | GitHub personal access token |
| `targetBranches` | (optional) | Comma-separated branches to push poms for |
| `triggerBranch` | (optional) | Branch whose deploy updates versions.yaml |
| `pomPath` | `pom.xml` | Path to pom.xml in your repo |

## Cron Resolver (Spring Boot)

The resolver is a Spring Boot application that runs once and exits (triggered by Jenkins cron).

**Configuration (application.yml):**

```yaml
github:
  token: ${GITHUB_TOKEN}       # from environment variable

resolver:
  branch-prefix: deps           # PR branch prefix
```

```bash
# Run with env var
GITHUB_TOKEN=your_token java -jar resolver-core.jar

# Or set env var in Jenkins (automatic via withCredentials)
```

## Registry

The registry lives in the `registry/` folder of this repo:

```
registry/
  versions.yaml                          # Trigger artifacts + latest versions
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
    pomPath: pom.xml
    updatedAt: "2026-04-08T10:30:00Z"
```

## Project Structure

```
dependency-resolver/
  pom.xml                                  # Parent POM
  resolver-common/                         # Shared classes
    github/GitHubClient.java               # GitHub REST API
    github/GitHubConflictException.java     # 409 exception
    registry/RegistryClient.java            # Registry read/write + discovery
    registry/ArtifactEntry.java             # Registry data model
    registry/VersionRegistry.java           # Registry root model
    registry/VersionComparator.java         # Semantic version comparison
  resolver-core/                           # Spring Boot app (cron resolver)
    ResolverApplication.java               # Spring Boot entry point
    config/AppConfig.java                  # Bean definitions (@Configuration)
    scheduler/ResolverScheduler.java       # Resolver logic (CommandLineRunner)
    github/PullRequestCreator.java         # PR creation + idempotency
    pom/PomParser.java                     # XML parsing (XXE-safe)
    pom/PomModifier.java                   # Regex-based version updates
    pom/PropertyResolver.java              # ${property} resolution
    scanner/DependencyMatch.java           # Version match DTO
  resolver-plugin/                         # Maven plugin
    plugin/RegisterVersionMojo.java        # Deploy-phase Mojo
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
  --> Skip entirely (not a target or trigger branch)

Cron resolver:
  --> Read versions.yaml for latest versions
  --> List all pom folders in registry (discover consumers + branches)
  --> For each consumer, for each branch:
      Read pom, compare versions (semver, no downgrades), create PR
      PR branch: deps/{targetBranch}/bump-{name}-to-{version}
```

## Build

```bash
# Build everything
mvn clean install

# Run tests
mvn clean test

# Package fat JAR
mvn clean package -pl resolver-core
# Output: resolver-core/target/resolver-core-1.0.0-SNAPSHOT.jar
```

Requires Java 21+.
