# Dependency Resolver — Architecture

## Overview

A one-shot CLI that updates a single target repo's `pom.xml` based on runtime release-group input. Jenkins invokes the resolver per `(owner, repo, branch)` plus release `groupId` selection. Resolver writes changes through pull requests (new branch per run), not direct base-branch commits.

## Flow

```
Jenkins (OWNER, REPO, BRANCH, RELEASE_GROUP_IDS)
        │
        ▼
ResolverRunner (ApplicationRunner)
        │
        ├─ 1) GitHubClient.getFileContent(pom)
        │
        ├─ 2) PomManager.listCoordinatesForGroupIds(...)
        │      scans dependencies, depMgmt, plugins,
        │      pluginMgmt, parent
        │
        ├─ 3) ArtifactoryClient.latestReleaseVersion(g, a)
        │      per discovered coordinate
        │
        ├─ 4) PomManager.findBumpsFromLatestVersions(...)
        │
        ├─ 5) PomManager.applyBumps(...)
        │
        └─ 6) GitHub writeback
               - get base branch SHA
               - create new run branch
               - commit updated pom on run branch
               - create PR to base branch
```

## Key Files

| File | Role |
|---|---|
| `runner/ResolverRunner.java` | CLI orchestration: args, scan, resolve, bump, branch, PR. |
| `pom/PomManager.java` | Maven model parsing, coordinate discovery by `groupId`, bump detection and in-place application. |
| `pom/BumpedDependency.java` | Version bump record. |
| `artifactory/ArtifactoryClient.java` | Artifactory metadata and artifact retrieval APIs (release path used in phase 1). |
| `artifactory/MavenMetadataParser.java` | Parses `maven-metadata.xml`. |
| `github/GitHubClient.java` | GitHub contents + branch + PR APIs. |
| `xml/SecureXmlParser.java` | XXE-safe XML parsing helpers. |
| `config/AppConfig.java` | Spring bean wiring. |

## Design Notes

- No central repository registry; each run is explicit.
- No cron inside resolver; Jenkins controls scheduling.
- Dependency scope is selected by runtime release-group IDs.
- Missing group matches are warnings, not failures.
- Writeback is PR-only to support review/branch protections.
