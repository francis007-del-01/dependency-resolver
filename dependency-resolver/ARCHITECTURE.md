# Dependency Resolver - Architecture

## Overview

A Spring Boot application that automatically updates Maven dependency versions across GitHub repositories. Reads a config file to know which repos to watch, fetches poms via GitHub API, and creates PRs or auto-merges when dependencies are outdated.

No plugins. No registry. No webhooks. One config file + GitHub API.

---

## Flow

```
                        CONFIG-BASED RESOLUTION FLOW
                        ============================

  +-------------------+
  | config.yaml       |
  | (in this repo)    |
  +-------------------+
          |
          v
  +-------------------+
  | ResolverScheduler |
  | (Spring Boot)     |
  +-------------------+
          |
          |  Phase 1: Read trigger versions
          |
          v
  +-------------------------------+
  | For each repo with            |
  | triggerBranch:                 |
  |   GET /repos/{o}/{r}/contents |
  |   /pom.xml?ref={branch}       |
  |   → parse groupId:artifactId  |
  |     :version                  |
  |   → get last committer        |
  +-------------------------------+
          |
          |  latestVersions map:
          |    com.myorg:core-lib → 3.0.0 (by @namin2)
          |    com.myorg:utils → 2.0.0 (by @john-doe)
          |
          |  Phase 2: Update target branches
          |
          v
  +-------------------------------+
  | For each repo with            |
  | targetBranches:               |
  |   For each branch:            |
  |     Fetch pom from branch     |
  |     Parse deps                |
  |     Compare (semver)          |
  |     If outdated:              |
  +-------------------------------+
         |                |
    autoMerge=false  autoMerge=true
         |                |
         v                v
  +-----------+    +-----------+
  | Create/   |    | Commit    |
  | update PR |    | directly  |
  | with      |    | with      |
  | @mentions |    | @mentions |
  +-----------+    +-----------+
```

---

## Config

```yaml
repos:
  - owner: myorg
    name: core-lib
    triggerBranch: master           # source of truth for version

  - owner: myorg
    name: my-service
    targetBranches:
      - name: main
        autoMerge: false            # PR
      - name: develop
        autoMerge: true             # direct commit
```

- **triggerBranch:** fetch pom from this branch → extract version → add to latest versions map
- **targetBranches:** fetch pom from each branch → diff against latest → PR or auto-merge
- A repo can have triggerBranch, targetBranches, or both

---

## Components

### ResolverScheduler
- Implements `CommandLineRunner` — runs once on startup, then exits
- Phase 1: builds latest version map from trigger repos
- Phase 2: processes target repos, delegates to PullRequestCreator
- Gets last committer from GitHub API for @mentions

### PullRequestCreator
- **createUpdatePr():** stable branch name `deps/{target}/dep-updates`, creates or updates existing PR
- **directCommit():** commits pom update directly to target branch with @mentions in commit message
- Batches multiple dep bumps into one PR/commit

### PomParser
- DOM-based XML parsing with XXE protection
- Handles direct versions, property references (`${prop}`), managed dependencies
- Inherits groupId/version from parent pom

### PomModifier
- Format-preserving version updates using indexOf (no regex, no DOM serialization)
- Handles direct, property, and managed version types
- Only the version value changes — whitespace, comments, structure preserved

### VersionComparator
- Semantic version comparison: `1.9.0 < 1.10.0` (numeric, not string)
- SNAPSHOT is older than release: `1.0.0-SNAPSHOT < 1.0.0`
- Never downgrades: `2.0.0` → `1.5.0` is skipped

### GitHubClient
- GitHub REST API: file read/write, branches, PRs, directory listing
- 409 conflict handling for concurrent writes
- Rate limit monitoring
- Last committer lookup for @mentions

---

## PR Behavior

| Scenario | What happens |
|----------|-------------|
| First run, deps outdated | Creates branch + PR |
| Second run, new version | Updates existing PR (new commit + updated body) |
| Multiple deps outdated | Batched into one PR |
| autoMerge=true | Direct commit to branch, @mention deployers |
| Already up to date | Skipped |
| Newer than latest | Skipped (no downgrades) |

PR branch name: `deps/{targetBranch}/dep-updates` (stable, reused across bumps)

---

## Version Detection

| Style | Example | How PomParser detects it | How PomModifier updates it |
|-------|---------|------------------------|---------------------------|
| Direct | `<version>1.0.0</version>` | Literal string | Replace within `<dependency>` block |
| Property | `<version>${lib.version}</version>` | Matches `${...}` pattern | Update `<lib.version>` in `<properties>` |
| Managed | Version in `<dependencyManagement>` | Found under that section | Same as direct |

---

## Safeguards

| Safeguard | How |
|-----------|-----|
| No downgrades | Semantic version comparison |
| XXE protection | DOM parser disables external entities |
| Format preservation | indexOf-based replacement, no DOM serialization |
| Idempotent | Stable branch name, reuses existing PR |
| Rate limit aware | Monitors X-RateLimit-Remaining header |
| Error resilient | Per-repo failures don't stop others |
| @mentions | Deployers notified via GitHub @mention in PR/commit |
