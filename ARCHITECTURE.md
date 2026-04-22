# Dependency Resolver — Architecture

## Overview

A one-shot CLI that updates a single target repo's pom dependencies. Invoked by Jenkins per-(owner, repo, branch). No central config, no cron, no gating heuristics — Artifactory is the authority on "what versions exist."

## Flow

```
  Jenkins (params: OWNER, REPO, BRANCH)
         │
         ▼
  +-----------------------------+
  | ResolverRunner              |
  | (ApplicationRunner)         |
  +-----------------------------+
         │
         │ 1. GitHubClient.getFileContent(pom)
         ▼
  +-----------------------------+
  | PomManager.readFetchDirec-  |
  | tives(pomContent)           |
  |   → <fetchLatest> deps      |
  |   → <fetchRelease> deps     |
  +-----------------------------+
         │
         │ 2. For each directive:
         ▼
  +-----------------------------+
  | ArtifactoryClient           |
  |  fetchLatest  →             |
  |    latestReleaseVersion +   |
  |    latestSnapshotBaseVersion|
  |    → download both jars →   |
  |    compare git.commit.id    |
  |    (same SHA = use release; |
  |     different = use SNAPSHOT)|
  |  fetchRelease → latestReleaseVersion(g, a)
  +-----------------------------+
         │
         │ 3. Build latestVersions map
         ▼
  +-----------------------------+
  | PomManager.findBumpsFromDi- |
  | rectives → List<Bumped...>  |
  | (scans deps, depMgmt,       |
  |  plugins, parent, properties)|
  +-----------------------------+
         │
         │ 4. Apply bumps in-place
         ▼
  +-----------------------------+
  | PomManager.applyBumps       |
  |   (format-preserving; uses  |
  |    Maven model + property   |
  |    resolution)              |
  +-----------------------------+
         │
         │ 5. Commit directly to BRANCH
         ▼
  +-----------------------------+
  | GitHubClient.updateFile     |
  +-----------------------------+
```

## Key files

| File | Role |
|---|---|
| `runner/ResolverRunner.java` | CLI entry; `ApplicationRunner` that parses `--owner/--repo/--branch/--pomPath`, orchestrates the flow. |
| `pom/PomManager.java` | Parses `<fetchLatest>/<fetchRelease>` directives; finds and applies version bumps (direct, property, managed, plugins, parent). |
| `pom/BumpedDependency.java` | Result record: `(groupId, artifactId, oldVersion, newVersion, updatedBy)`. |
| `artifactory/ArtifactoryClient.java` | `latestReleaseVersion(g, a)` + `latestSnapshotBaseVersion(g, a)` (from `maven-metadata.xml`); jar fetchers `getReleaseGitInfo(g, a, v)` / `getSnapshotGitInfo(g, a, baseVersion)` extract `git.commit.id`; `getReleaseScm(g, a, v)` extracts the library's GitHub `owner/name` from the release jar's embedded pom. Caches raw jar bytes, git info, and SCM by `(g:a:version)` forever — timestamped SNAPSHOT versions are immutable, releases never move. XXE-hardened. |
| `artifactory/GitPropertiesExtractor.java` | Streams a jar's zip entries to pull `git.commit.id` + `git.dirty` from `META-INF/git.properties`. |
| `artifactory/JarScmExtractor.java` | Streams a jar's zip entries to find `META-INF/maven/<g>/<a>/pom.xml`, parses `<scm>` (url / connection / developerConnection), matches a GitHub URL pattern (github.com + GHE hosts + SSH form) to extract `owner/name`. |
| `config/ServiceUserProperties.java` | Bot-author list from `application.yml`. Matches name + email case-insensitively. |
| `artifactory/ArtifactoryProperties.java` | Spring `@ConfigurationProperties` for `base-url`, `release-repo`, `snapshot-repo`, `token`. |
| `github/GitHubClient.java` | File read/write via Contents API. |
| `version/VersionComparator.java` | Semver-aware "is older than" used by the bump check. |
| `config/AppConfig.java` | Bean wiring. |

## Custom pom directives

Maven silently ignores unknown top-level elements under `<project>`, so `<fetchLatest>` and `<fetchRelease>` are safe to include in a normal pom. They're never consumed by Maven itself — only by this resolver.

```xml
<fetchLatest>
  <dependency>
    <groupId>...</groupId>
    <artifactId>...</artifactId>
  </dependency>
</fetchLatest>
```

Only `<groupId>` and `<artifactId>` are read. `<version>` (if present) is ignored — the resolver pulls the current version from where the dep actually lives in the pom (`<dependencies>`, `<dependencyManagement>`, `<plugins>`, `<parent>`, or a `<properties>` property ref).

## What this design deliberately drops

- **Cron** — nothing schedules itself. Jenkins (or any caller) decides when + where.
- **Central config.yaml** — no list of repos, branches, or service users.
- **Branch-based gating** — no more "scan master/develop for human commits since the tag." The gate uses two signals: `git.commit.id` equality between the release and SNAPSHOT jars (shortcut: same SHA → same source → use release), and, when SHAs differ, GitHub `compare releaseSha...snapshotSha` with a service-user filter (zero human commits → use release; else → use SNAPSHOT). The library's GitHub repo is discovered at runtime from the release jar's embedded `<scm>`.
- **PR creation** — commits directly to the target branch. If a repo wants PR-based review, that's a Jenkinsfile concern (commit to a feature branch and open a PR there).
- **@mentions / committer tracking** — unnecessary when the caller already knows who triggered the job.

## Fallback when `git.properties` is missing

If either the release or SNAPSHOT jar lacks `META-INF/git.properties` (library hasn't adopted `git-commit-id-maven-plugin`), the resolver can't compare SHAs. It falls back to preferring the SNAPSHOT and logs a WARN. Same for `git.dirty=true` — we can't trust a SHA from a dirty build.

## Build & run

See `README.md`.
