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

Produces `resolver-core/target/resolver-core-1.0.0-SNAPSHOT.jar`.

## Run locally

```bash
export GITHUB_TOKEN=...
export ARTIFACTORY_TOKEN=...
export ARTIFACTORY_BASE_URL=https://artifactory.a.intuit.com/artifactory
export ARTIFACTORY_RELEASE_REPO=maven.billingcomm-custpayment.ngp-releases
export ARTIFACTORY_SNAPSHOT_REPO=maven.billingcomm-custpayment.ngp-snapshots

java -jar resolver-core/target/resolver-core-1.0.0-SNAPSHOT.jar \
  --owner=myorg \
  --repo=my-service \
  --branch=develop
```

Requires Java 21+.
