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
| `pom/PomManager.java` | Parses `<fetchLatest>/<fetchRelease>` directives; finds and applies version bumps (direct, property, managed, plugins, parent); also owns `extractGitHubCoords(pomContent)` which parses `<scm>` (url / connection / developerConnection) and matches a GitHub URL pattern (github.com + GHE hosts + SSH form) to extract `owner/name`. |
| `pom/BumpedDependency.java` | Result record: `(groupId, artifactId, oldVersion, newVersion, updatedBy)`. |
| `artifactory/ArtifactoryClient.java` | HTTP fetches against Artifactory: `latestReleaseVersion(g, a)` / `latestSnapshotBaseVersion(g, a)` (read `maven-metadata.xml`); `getReleaseGitInfo(g, a, v)` / `getSnapshotGitInfo(g, a, baseVersion)` fetch the jar and extract `git.commit.id` from `META-INF/git.properties`; `getReleaseScm(g, a, v)` fetches the release jar, pulls out the embedded `META-INF/maven/*/pom.xml`, and delegates SCM parsing to `PomManager`. Stateless — no caching (each run processes one repo then exits). |
| `artifactory/MavenMetadataParser.java` | Parses `maven-metadata.xml` bytes into `latestReleaseVersion`, `latestSnapshotBaseVersion`, and `latestTimestampedJarVersion` (resolves `1.2.3-SNAPSHOT` → actual timestamped filename like `1.2.3-20260423.161847-3`). |
| `xml/SecureXmlParser.java` | Shared XXE-hardened `DocumentBuilderFactory` + `textOfChild` helper. Single source of truth for secure XML parsing across `PomManager` and `MavenMetadataParser`. |
| `github/GitHubClient.java` | File read/write via Contents API; `compareCommits(owner, repo, base, head)` returns authors between two SHAs. |
| `version/VersionComparator.java` | Semver-aware "is older than" used by the bump check. |
| `config/AppConfig.java` | Bean wiring. Owns `@EnableConfigurationProperties` for all three properties classes. |
| `config/*Properties.java` | Spring `@ConfigurationProperties`: `ArtifactoryProperties` (`artifactory.*`), `GitHubProperties` (`github.*`), `ServiceUserProperties` (`resolver.service-user.*`). All co-located in `config/`. |

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
