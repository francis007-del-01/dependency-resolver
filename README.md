# Dependency Resolver

Automatically keeps Maven dependency versions up to date across repositories by creating pull requests or auto-merging.

## High-Level Design

```
+------------------+          +------------------------------------------+
|  Trigger Repos   |          |  This Repo (dependency-resolver)         |
|  (libraries)     |          |                                          |
|  +-----------+   |          |  +------------------------------------+  |
|  | core-lib  |   |  GitHub  |  | config.yaml                        |  |
|  | (v3.0.0)  |---+--API---->|  |   trigger repos + target repos     |  |
|  +-----------+   |          |  |   per-branch autoMerge config       |  |
|  +-----------+   |          |  +------------------------------------+  |
|  | utils     |   |          |                  |                       |
|  | (v2.0.0)  |---+--------->|                  | Jenkins cron          |
|  +-----------+   |          |                  v                       |
+------------------+          |  +------------------------------------+  |
                              |  | CronResolverMain (Spring Boot)     |  |
+------------------+          |  |                                    |  |
|  Target Repos    |          |  | 1. Read config.yaml                |  |
|  (services)      |          |  | 2. Fetch pom from trigger branches |  |
|       ^          |          |  |    → build latest versions map     |  |
|       |          |          |  | 3. Fetch pom from target branches  |  |
|  +-----------+   |          |  |    → diff deps against latest      |  |
|  | service-b |<--+--PR/-----+  | 4. autoMerge=true → direct commit |  |
|  +-----------+   |  merge   |  |    autoMerge=false → create PR     |  |
|  +-----------+   |          |  +------------------------------------+  |
|  | service-c |<--+----------+                                          |
|  +-----------+   |          +------------------------------------------+
+------------------+
```

## How It Works

```
Jenkins cron (every 10 min)
  |
  +-- Read config.yaml
  |
  +-- For each trigger repo:
  |     Fetch pom.xml from trigger branch via GitHub API
  |     Read groupId:artifactId:version
  |     Read last committer (for @mentions)
  |     → Build latest versions map
  |
  +-- For each target repo, for each branch:
  |     Fetch pom.xml from that branch via GitHub API
  |     Parse dependencies (direct, property, managed)
  |     Compare against latest versions (semver, no downgrades)
  |     If outdated:
  |       autoMerge=true  → commit directly, @mention deployers
  |       autoMerge=false → create/update PR with deployer info
  +-- Done
```

No plugin. No registry. No `mvn deploy` hook. Just one config file.

## Quick Start

### 1. Create config.yaml

```yaml
repos:
  # Libraries — read their latest version from trigger branch
  - url: https://github.com/myorg/core-lib
    triggerBranch: master

  - url: https://github.com/myorg/utils
    triggerBranch: master

  # Services — update their deps on target branches
  - url: https://github.com/myorg/my-service
    targetBranches:
      - name: main
        autoMerge: false    # creates PR
      - name: develop
        autoMerge: true     # commits directly

  # Library that does both
  - url: https://github.com/myorg/shared-lib
    triggerBranch: master
    targetBranches:
      - name: master
        autoMerge: false
      - name: develop
        autoMerge: true
```

### 2. Set GitHub token

```bash
export GITHUB_TOKEN=ghp_your_token_here
```

### 3. Run

```bash
java -jar resolver-core.jar
```

Or set up Jenkins cron (see `jenkins-shared-lib/Jenkinsfile`).

## Config Reference

```yaml
repos:
  - url: https://github.com/myorg/core-lib   # GitHub repo URL (required)
    pomPath: pom.xml                           # path to pom.xml (default: pom.xml)
    triggerBranch: master                      # read version from this branch (optional)
    targetBranches:                            # update deps on these branches (optional)
      - name: main
        autoMerge: false                       # false = PR, true = direct commit
      - name: develop
        autoMerge: true
```

- **url** — GitHub repo URL. Owner and name are derived from it.
- **triggerBranch** — the cron reads this repo's pom to get the latest version. Set for libraries that publish versions.
- **targetBranches** — the cron checks these branches for outdated deps and creates PRs or auto-merges. Set for services/libs that consume dependencies.
- A repo can have both (library that also consumes other libraries).

## Application Config

`application.yml`:
```yaml
github:
  token: ${GITHUB_TOKEN}

resolver:
  branch-prefix: deps
  parallelism: 10
  config-path: classpath:config.yaml
```

## What happens on each run

| Trigger repo | What the cron does |
|---|---|
| `core-lib` (triggerBranch: master) | Fetches `pom.xml` from `core-lib/master`, reads version `3.0.0`, notes last committer `@namin2` |

| Target repo | Branch | autoMerge | What the cron does |
|---|---|---|---|
| `service-b` | main | false | Fetches pom, finds `core-lib 1.5.0` → outdated → creates PR with `@namin2` in body |
| `service-b` | develop | true | Fetches pom, finds `core-lib 1.8.0` → outdated → commits directly, `@namin2` in commit message |
| `service-c` | main | false | Fetches pom, finds `${core-lib.version}=1.3.0` → outdated → creates PR, property updated |

## PR Behavior

- **Stable branch name:** `deps/{targetBranch}/dep-updates` — no version in name
- **First run:** creates branch + PR
- **Second run (new version):** updates the same PR (new commit + updated body)
- **Multiple deps outdated:** batched into one PR
- **autoMerge:** commits directly to target branch, @mentions all deployers

### PR body example:
```
## Automated Dependency Update

- `com.myorg:core-lib` from `1.5.0` to `3.0.0` (deployed by @namin2)
- `com.myorg:utils` from `1.2.0` to `2.0.0` (deployed by @john-doe)

This PR was created automatically by the dependency-resolver.
```

## Project Structure

```
dependency-resolver/
  pom.xml                                    # Parent POM
  resolver-core/                             # Spring Boot app
    src/main/resources/
      config.yaml                            # Repo configuration
      application.yml                        # App settings
    src/main/java/com/depresolver/
      ResolverApplication.java               # Spring Boot entry
      config/
        AppConfig.java                       # Bean definitions
        ResolverConfig.java                  # Config root model
        RepoConfig.java                      # Per-repo config
        BranchConfig.java                    # Per-branch config
      scheduler/
        ResolverScheduler.java               # Main logic (CommandLineRunner)
      github/
        GitHubClient.java                    # GitHub REST API
        PullRequestCreator.java              # PR create/update + direct commit
      pom/
        PomParser.java                       # DOM-based XML parsing
        PomModifier.java                     # Format-preserving version updates
        PropertyResolver.java                # ${property} resolution
      version/
        VersionComparator.java               # Semantic version comparison
      scanner/
        DependencyMatch.java                 # Version match DTO
  jenkins-shared-lib/
    Jenkinsfile                              # Jenkins cron pipeline
```

## Build

```bash
# Build
mvn clean package -pl resolver-core -DskipTests

# Run tests
mvn clean test -pl resolver-core

# Run locally
GITHUB_TOKEN=your_token java -jar resolver-core/target/resolver-core-1.0.0-SNAPSHOT.jar
```

Requires Java 21+.
