# Dependency Resolver

A per-repo CLI tool that updates Maven dependency versions on a target branch. Invoked by Jenkins with `(owner, repo, branch)`; the target repo's own `pom.xml` declares which dependencies to track by embedding `<fetchLatest>` and `<fetchRelease>` elements.

## How It Works

```
Jenkins job (parameterized: OWNER, REPO, BRANCH)
  │
  ▼
java -jar resolver-core.jar --owner=... --repo=... --branch=... [--pomPath=pom.xml]
  │
  ├── Fetch pom.xml from GitHub (owner/repo @ branch)
  │
  ├── Parse <fetchLatest> and <fetchRelease> directives
  │     <fetchLatest>
  │       <dependency><groupId>...</groupId><artifactId>...</artifactId></dependency>
  │       ...
  │     </fetchLatest>
  │     <fetchRelease>
  │       <dependency>...</dependency>
  │     </fetchRelease>
  │
  ├── For each fetchLatest dep  → Artifactory snapshot repo → latest SNAPSHOT base version
  ├── For each fetchRelease dep → Artifactory release repo  → latest release version
  │
  ├── Diff against the pom's current versions (deps, depMgmt, plugins, parent)
  │
  └── If anything outdated: apply bumps → commit directly to BRANCH
```

No cron, no central config, no SNAPSHOT gating heuristics. Each target repo declares what it wants; Artifactory is the authority on "what exists."

## Example: target pom.xml

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>my-service</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <dependencies>
    <dependency>
      <groupId>com.intuit.payment.common</groupId>
      <artifactId>pymt-lib</artifactId>
      <version>1.0.443.0</version>
    </dependency>
    <dependency>
      <groupId>com.intuit.payment</groupId>
      <artifactId>pymt-schema</artifactId>
      <version>2.1.0</version>
    </dependency>
  </dependencies>

  <!-- Custom directives read by the resolver. Maven ignores unknown elements. -->
  <fetchLatest>
    <dependency>
      <groupId>com.intuit.payment.common</groupId>
      <artifactId>pymt-lib</artifactId>
    </dependency>
  </fetchLatest>
  <fetchRelease>
    <dependency>
      <groupId>com.intuit.payment</groupId>
      <artifactId>pymt-schema</artifactId>
    </dependency>
  </fetchRelease>
</project>
```

When the Jenkins job runs against this repo:

- `pymt-lib` (fetchLatest) → resolver asks Artifactory for the latest SNAPSHOT (`1.0.444.0-SNAPSHOT`) and the latest release (`1.0.443.0`), then downloads both jars and compares the `git.commit.id` each jar carries in `META-INF/git.properties`. If the SHAs match → source hasn't moved → use release. If they differ → resolver reads the library's `<scm>` from the release jar's embedded pom, calls GitHub `compare releaseSha...snapshotSha` for that repo, and filters out service-user commits. **Zero human commits between the two → use release** (SNAPSHOT only has bot noise); **one or more human commits → use SNAPSHOT** (real changes).
- `pymt-schema` (fetchRelease) → resolver asks Artifactory for the latest release → `2.2.0` → pom gets bumped to `2.2.0`. No SNAPSHOT involvement.
- Commit pushed directly to `BRANCH`.

### SHA comparison for `fetchLatest`

For the SHA comparison to work, libraries listed under `fetchLatest` must publish `META-INF/git.properties` inside their jar (via `git-commit-id-maven-plugin`). Add to the library's root pom:

```xml
<plugin>
  <groupId>io.github.git-commit-id</groupId>
  <artifactId>git-commit-id-maven-plugin</artifactId>
  <version>9.0.1</version>
  <executions>
    <execution>
      <goals><goal>revision</goal></goals>
      <phase>initialize</phase>
    </execution>
  </executions>
  <configuration>
    <includeOnlyProperties>
      <property>git.commit.id</property>
      <property>git.dirty</property>
    </includeOnlyProperties>
    <failOnNoGitDirectory>false</failOnNoGitDirectory>
  </configuration>
</plugin>
```

**Fallback when `git.properties` is missing** (library hasn't adopted the plugin yet): the resolver prefers the SNAPSHOT version. Safe default — downstream consumers see the pom-declared SNAPSHOT and can decide what to do with it. A WARN is logged so you know which library still needs the plugin.

**Fallback when `<scm>` is missing** from the release jar's embedded pom: the resolver can't reach out to GitHub to classify commits as human vs. bot. It prefers the SNAPSHOT (conservative) and logs a WARN. To enable bot filtering, ensure the library's root pom declares `<scm><url>` or `<scm><connection>` pointing at the GitHub repo.

**Service-user list** in `application.yml`:

```yaml
resolver:
  service-user:
    names:
      - root
    emails:
      - Tech-t4i-svc-dbill-automation@intuit.com
```

Any commit whose author name or email matches (case-insensitive) is treated as a bot.

## Usage

End-to-end walkthrough for operators (running the tool) and repo owners (wiring up their pom).

### 1. Onboarding a target repo (repo owner task)

Before the resolver can touch a repo, that repo needs to tell it what to track. This is one-time setup per repo.

**a. Add directives to the repo's `pom.xml`.** Pick one or both depending on what you want:

- `<fetchLatest>` — resolver decides between the newest release and the newest SNAPSHOT by comparing `git.commit.id`s (falls back to SNAPSHOT if info is missing). Use this for libraries you co-develop, where you want the freshest safe build.
- `<fetchRelease>` — resolver only considers releases, never SNAPSHOTs. Use this for stable third-party-style deps.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  ...
  <!-- Existing dependencies stay exactly where they are -->

  <fetchLatest>
    <dependency>
      <groupId>com.intuit.payment.common</groupId>
      <artifactId>pymt-lib</artifactId>
    </dependency>
  </fetchLatest>

  <fetchRelease>
    <dependency>
      <groupId>com.intuit.payment</groupId>
      <artifactId>pymt-schema</artifactId>
    </dependency>
  </fetchRelease>
</project>
```

Maven ignores unknown top-level elements, so these directives don't affect your normal build. Only `<groupId>` + `<artifactId>` are read — no version needed (the resolver infers current version from wherever the dep actually lives in the pom).

**b. For `fetchLatest` libraries only:** make sure the library (the one being tracked, not your repo) publishes `META-INF/git.properties` in its jar. See ["SHA comparison for `fetchLatest`"](#sha-comparison-for-fetchlatest) above for the plugin snippet. Without this, the resolver can't compare release vs. SNAPSHOT SHAs and will always prefer SNAPSHOT (safe default, logs a WARN).

**c. For `fetchLatest` libraries only:** make sure the library declares `<scm>` in its root pom pointing at the GitHub repo:

```xml
<scm>
  <url>https://github.com/myorg/pymt-lib</url>
  <connection>scm:git:https://github.com/myorg/pymt-lib.git</connection>
</scm>
```

Without this, the resolver can't filter bot commits via GitHub `compare` and will fall back to SNAPSHOT.

### 2. Running the resolver

Three ways to invoke it. All three need the same env vars.

#### Option A — Local / ad-hoc

Quickest path for testing or a one-off bump.

```bash
# Build once
mvn -pl resolver-core clean package

# Export credentials (Artifactory base URL + repo names usually don't change)
export GITHUB_TOKEN=ghp_...
export ARTIFACTORY_TOKEN=...
export ARTIFACTORY_BASE_URL=https://artifactory.a.intuit.com/artifactory
export ARTIFACTORY_RELEASE_REPO=maven.billingcomm-custpayment.ngp-releases
export ARTIFACTORY_SNAPSHOT_REPO=maven.billingcomm-custpayment.ngp-snapshots

# Run it
java -jar resolver-core/target/resolver-core-1.0.0-SNAPSHOT.jar \
  --owner=myorg \
  --repo=my-service \
  --branch=develop
```

The resolver will fetch `pom.xml` at HEAD of `develop`, compute bumps, and push a commit **directly to `develop`** if anything's outdated. If nothing needs updating, it exits without committing.

> **Heads up:** this writes to the branch. Always test against a throwaway branch first.

#### Option B — Jenkins (shared-lib pipeline)

Two Jenkinsfiles live in `jenkins-shared-lib/`, covering the two common Jenkins shapes:

| File | When to use | What it does |
|---|---|---|
| `Jenkinsfile-simple` | Agent image has the jar pre-baked at `/opt/resolver-core.jar` | Just runs the jar. Fastest builds. |
| `Jenkinsfile` | Source is checked out on the agent (no pre-baked jar) | Builds the jar with Maven first, then runs it. |

Both take `OWNER`, `REPO`, `BRANCH`, and optional `POM_PATH` as build parameters and require the same credentials.

**One-time Jenkins setup:**

1. Add two string credentials to Jenkins:
   - `github-api-token` — a PAT with `repo` scope.
   - `artifactory-token` — an Artifactory identity token with read on both release and snapshot repos.
2. Export `ARTIFACTORY_BASE_URL`, `ARTIFACTORY_RELEASE_REPO`, `ARTIFACTORY_SNAPSHOT_REPO` as Jenkins global env vars (or rely on the defaults in `Jenkinsfile`, or bake them into the agent image).
3. For `Jenkinsfile-simple`: bake the resolver jar into your agent image at `/opt/resolver-core.jar` (see `docker/Dockerfile` for a starter image).
   For `Jenkinsfile`: make sure the source tree is available at `/var/jenkins_home/project` on the agent, with Maven installed.
4. Create a parameterized pipeline job pointed at whichever Jenkinsfile fits your setup.

**Per-run:** trigger the job with the repo parameters. Jenkins injects the credentials, runs the jar, and logs the resolver's output to the build log.

#### Option C — Docker

For CI systems other than Jenkins, or for a cleanly sandboxed local run.

```bash
# Build the jar
mvn -pl resolver-core clean package

# Run in an eclipse-temurin:21 container with the jar mounted
docker run --rm \
  -e GITHUB_TOKEN \
  -e ARTIFACTORY_TOKEN \
  -e ARTIFACTORY_BASE_URL \
  -e ARTIFACTORY_RELEASE_REPO \
  -e ARTIFACTORY_SNAPSHOT_REPO \
  -v "$PWD/resolver-core/target/resolver-core-1.0.0-SNAPSHOT.jar:/opt/resolver.jar:ro" \
  eclipse-temurin:21-jre \
  java -jar /opt/resolver.jar \
    --owner=myorg --repo=my-service --branch=develop
```

### 3. What to expect in the output

Run the resolver with `--branch=develop` against a repo that has one `fetchLatest` dep needing an update. Typical log:

```
Resolving for myorg/my-service@develop (pom=pom.xml)
Read pom.xml @ 3a7f2c... (sha)
fetchLatest directives: [com.intuit.payment.common:pymt-lib]
fetchRelease directives: []
  fetchLatest com.intuit.payment.common:pymt-lib
    latest release  = 1.0.443.0
    latest snapshot = 1.0.444.0-SNAPSHOT
    release SHA     = a91ea83f (clean)
    snapshot SHA    = d1972ed4 (clean)
    SHAs differ → checking commits
    compare myorg/pymt-lib a91ea83f...d1972ed4 → 3 commits, 1 human
    → using SNAPSHOT (1.0.444.0-SNAPSHOT)
Bumps: com.intuit.payment.common:pymt-lib 1.0.443.0 → 1.0.444.0-SNAPSHOT
Applied 1 bump(s)
Updated pom.xml on branch develop in myorg/my-service
```

Possible outcomes per dep:

| Log tail | Meaning |
|---|---|
| `same SHA → using release` | Release and SNAPSHOT came from identical source; picking release. |
| `zero human commits → using release` | SNAPSHOT has only bot commits since release; picking release. |
| `N human commits → using SNAPSHOT` | Real changes in SNAPSHOT; picking SNAPSHOT. |
| `no git.properties → preferring SNAPSHOT` | Library jar lacks the plugin; falling back. |
| `no <scm> → preferring SNAPSHOT` | Can't reach GitHub to classify; falling back. |
| `no bumps` | Pom's current version is already at or ahead of the resolved latest. |

### 4. Verifying a run

- **Check the branch:** the resolver commits under the GitHub token's identity. `git log -1 BRANCH` on your local clone (after `git pull`) shows the bump commit with a generated message listing each `(g:a) old → new`.
- **Diff the pom:** the resolver touches only `<version>` elements (or `<properties>` entries when versions are property-refs). Whitespace and element order are preserved.
- **Re-run is idempotent:** running the resolver again against the same branch with nothing new in Artifactory is a no-op — no commit, no error.

### 5. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `401 Unauthorized` from GitHub | Token missing `repo` scope, or expired | Regenerate PAT, re-set `GITHUB_TOKEN` |
| `403` from Artifactory | Token doesn't have read access on one of the configured repos | Grant read on both `*-releases` and `*-snapshots` |
| `fetchLatest` always picks SNAPSHOT for a specific lib | Library missing `git.properties` or `<scm>` | Add `git-commit-id-maven-plugin` / `<scm>` block to that library's pom |
| Resolver says "no bumps" but you see a newer version in Artifactory | Artifactory `maven-metadata.xml` not yet updated (eventual consistency after deploy), or your pom already at that version | Wait ~1 min and re-run; confirm pom's current version |
| Commit pushed but CI didn't trigger | Target branch has branch-protection rules that require PRs | Run against a feature branch instead, then open a PR manually |
| `CannotResolve`/`IOException` with URL in message | Env var misconfigured (typo in repo name or base URL) | Double-check all five `ARTIFACTORY_*` env vars |

## Environment

Required env vars (set as Jenkins credentials):

| Var | Purpose |
|---|---|
| `GITHUB_TOKEN` | Fetch + commit pom via GitHub API |
| `ARTIFACTORY_TOKEN` | Fetch `maven-metadata.xml` from Artifactory |
| `ARTIFACTORY_BASE_URL` | e.g. `https://artifactory.a.intuit.com/artifactory` |
| `ARTIFACTORY_RELEASE_REPO` | e.g. `maven.billingcomm-custpayment.ngp-releases` |
| `ARTIFACTORY_SNAPSHOT_REPO` | e.g. `maven.billingcomm-custpayment.ngp-snapshots` |

## CLI arguments

| Flag | Required | Default |
|---|---|---|
| `--owner` | yes | — |
| `--repo` | yes | — |
| `--branch` | yes | — |
| `--pomPath` | no | `pom.xml` |

## Build

```bash
mvn -pl resolver-core clean package
```

Produces `resolver-core/target/resolver-core-1.0.0-SNAPSHOT.jar`. Requires Java 21+.

For running instructions, see [Usage → Running the resolver](#2-running-the-resolver) above.
