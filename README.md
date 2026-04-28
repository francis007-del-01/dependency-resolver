# Dependency Resolver

A per-repo CLI tool that updates Maven dependency versions on a target repository by:

1. reading the target `pom.xml` from GitHub,
2. selecting dependencies by runtime-provided release `groupId`s,
3. resolving latest release versions from Artifactory,
4. updating `pom.xml`,
5. opening a new pull request (new branch per run).

## Runtime Model

Dependency selection is runtime-driven. The resolver no longer reads custom directive blocks from the pom.

- You pass one or more `--releaseGroupId` arguments.
- The resolver scans the target pom and tracks all matching entries across:
  - dependencies
  - dependencyManagement
  - plugins
  - pluginManagement
  - parent
- For each discovered `(groupId, artifactId)`, it reads latest release from Artifactory.
- If the pom needs updates, it creates:
  - a fresh branch for this run
  - one commit with updated `pom.xml`
  - one PR to the input `--branch`.

## CLI Arguments

| Flag | Required | Default | Notes |
|---|---|---|---|
| `--owner` | yes | — | GitHub owner/org of target repo |
| `--repo` | yes | — | GitHub repo name of target repo |
| `--branch` | yes | — | Base branch for PR |
| `--releaseGroupId` | yes (repeatable) | — | One or more groupIds to track |
| `--pomPath` | no | `pom.xml` | Target pom path in repo |

Examples:

```bash
java -jar resolver-core/target/resolver-core-1.0.0-SNAPSHOT.jar \
  --owner=myorg \
  --repo=my-service \
  --branch=main \
  --releaseGroupId=com.acme.billing \
  --releaseGroupId=com.acme.plugins
```

Comma-separated input is also accepted:

```bash
--releaseGroupId=com.acme.billing,com.acme.plugins
```

## What Gets Updated

For matching groupIds, resolver checks version-bearing entries and updates only when current version is older:

- direct dependency versions
- dependencyManagement versions
- build plugin versions
- pluginManagement versions
- parent version
- backing property values when versions are property references

If a provided groupId has no matches in pom, resolver logs a warning and continues.

## Writeback Behavior

- Resolver never commits directly to base branch.
- Every run creates a new branch with a resolver-generated name.
- Resolver commits updated `pom.xml` on that branch.
- Resolver creates a PR targeting `--branch`.
- Jenkins logs include created PR number and URL.

## Environment Variables

| Var | Purpose |
|---|---|
| `GITHUB_TOKEN` | GitHub API read/write for source + PR |
| `ARTIFACTORY_TOKEN` | Artifactory read token |
| `ARTIFACTORY_BASE_URL` | Artifactory base URL |
| `ARTIFACTORY_RELEASE_REPO` | Release Maven repository name |
| `ARTIFACTORY_SNAPSHOT_REPO` | Snapshot repository name (reserved for future latest-mode work) |

## Build

```bash
mvn -pl resolver-core clean package
```

## Jenkins

`jenkins-shared-lib/Jenkinsfile` is wired for this runtime contract.

Set:

- `OWNER`, `REPO`, `BRANCH`, optional `POM_PATH`
- `RELEASE_GROUP_IDS` (comma-separated groupIds)

The pipeline converts `RELEASE_GROUP_IDS` into repeatable `--releaseGroupId` args.
