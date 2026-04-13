# Design Decisions & Trade-offs

## Why Spring Boot app over a Jenkins script?

We built both approaches and tested them. The Spring Boot app won.

### What we tried

**Approach 1: Jenkins Groovy pipeline script**
- Single Jenkinsfile (~330 lines) with all logic inline
- Python for XML parsing, curl for GitHub API
- No Java build step needed

**Approach 2: Spring Boot app + config.yaml**
- Java application with proper structure
- Config-based (single YAML file)
- Jenkins just builds the JAR and runs it

### Why the Jenkins script failed in practice

1. **Token passing is broken** — Jenkins `withCredentials` sets `$GH_TOKEN` as an env var, but passing it to `curl -H "Authorization: Bearer $GH_TOKEN"` requires shell quoting that conflicts with Groovy string interpolation. Every approach we tried had issues:
   - Double-quoted Groovy string: `$GH_TOKEN` gets Groovy-interpolated (not shell-expanded)
   - Single-quoted Groovy string: can't embed Groovy variables for dynamic URLs
   - Escaped quotes `\"`: Groovy compilation errors
   - Shell script files: Jenkins sandbox prevents some file operations
   - `'Bearer '\$GH_TOKEN`: shell splits the `-H` value into separate args

2. **CPS serialization errors** — Jenkins pipeline runs in a Continuation Passing Style (CPS) engine. `JsonSlurper` returns `LazyMap` which can't be serialized between pipeline steps. Every data structure needs manual conversion to `HashMap`.

3. **Groovy sandbox restrictions** — Jenkins restricts what Groovy code can run. Many standard library calls require script approval. Complex logic hits sandbox limits.

4. **No unit testing** — You can't unit test a Jenkinsfile. The only way to test is to run it in Jenkins and read console output. Every fix requires a full pipeline run (30-60 seconds).

5. **Error handling is primitive** — `sh` returns exit code or stdout, not both. Errors get swallowed. Debugging requires adding `echo` statements and re-running.

6. **String escaping hell** — Passing XML/JSON content through Groovy → shell → Python requires multiple layers of escaping. One wrong quote breaks everything.

### Why Spring Boot works

1. **No escaping issues** — Java handles HTTP directly via `HttpClient`. No shell, no curl, no quoting.

2. **Proper types** — Jackson deserializes YAML/JSON into typed objects. No `LazyMap`, no `HashMap` conversion.

3. **Testable** — Unit tests run in milliseconds. PomParser, PomModifier, VersionComparator all have tests.

4. **Debuggable** — Stack traces, logging levels, IDE debugger support.

5. **Token handling** — `application.yml` reads `${GITHUB_TOKEN}` from environment. HttpClient passes it as a header. No shell involved.

6. **Jenkins is simple** — Jenkins just does `mvn package` then `java -jar`. Two lines. No logic in the pipeline.

### The trade-off we accepted

| | Jenkins Script | Spring Boot |
|---|---|---|
| Lines of code | ~330 (one file) | ~1500 (multiple files) |
| Dependencies | Python, curl | JDK, Maven, Spring Boot |
| Build step | None | `mvn package` (~10 sec) |
| Startup time | Instant | ~0.5 sec (Spring Boot) |
| Testability | None | Full unit tests |
| Debuggability | Console logs only | IDE, stack traces, logging |
| Maintainability | Anyone reads Groovy | Need Java knowledge |
| Token handling | Broken in practice | Works reliably |

**Decision: Spring Boot app.** The Jenkins script approach is simpler in theory but broken in practice due to Groovy/shell quoting issues that are fundamental to how Jenkins pipelines work.

---

## Architecture Evolution

The system went through 5 major iterations:

### V1: Webhook CLI
- Jenkins webhook triggers CLI when artifact published
- CLI scans repos via GitHub API, creates PRs
- **Problem:** Required Nexus webhooks, Jenkins shared library, manually maintained repo list

### V2: Maven Plugin + Registry
- Libraries self-register via Maven plugin at deploy time
- Plugin pushes pom + version to a central registry
- Cron job reads registry, diffs versions, creates PRs
- **Problem:** Services that don't `mvn deploy` can't self-register

### V3: Multi-module with Spring Boot
- Split into resolver-common, resolver-core, resolver-plugin
- Spring Boot with DI, CommandLineRunner
- Per-branch autoMerge config
- **Problem:** Over-engineered. Plugin + registry + 3 modules for what's essentially "diff and PR"

### V4: Config-based (current)
- Single config.yaml lists all repos
- Spring Boot app reads config, fetches poms from GitHub directly
- No plugin, no registry, no pom copies
- Trigger branches = source of truth for versions
- Target branches = where to create PRs or auto-merge
- **This is the final version.**

### What got eliminated
- Nexus webhooks → gone
- Jenkins shared library (Groovy) → gone
- WebhookPayloadParser → gone
- Maven plugin (Mojo, plugin descriptor) → gone
- Registry folder (versions.yaml, pom copies) → gone
- resolver-common module → gone
- resolver-plugin module → gone
- RegistryClient, ArtifactEntry, VersionRegistry → gone
- GitClient, FileContentClient, BaseGitHubClient → gone
- Branch detection logic → gone
- registryOwner, registryRepo, registryBranch params → gone

---

## Testing

### What was tested

**Unit tests (38 tests, all pass):**
- PomParser: direct version, property version, managed deps, parent inheritance, multi-module detection
- PomModifier: direct update, property update, managed update, formatting preservation, idempotency
- PropertyResolver: property resolution, non-property passthrough
- ResolverTest: outdated dep detection, property updates, batched bumps, config deserialization

**End-to-end tests (real GitHub repos):**

| Test | Setup | Result |
|------|-------|--------|
| Trigger version detection | Created `core-lib` + `utils` repos with pom.xml | Versions read correctly from trigger branches |
| Direct version bump | `test-service-b/main` had core-lib 1.5.0, latest was 3.0.0 | PR created with version bumped to 3.0.0 |
| Multiple deps in one PR | `test-service-b` depends on core-lib + utils | Both bumped in single PR |
| Property version bump | `test-service-c` used `${core-lib.version}` | Property updated, `${}` reference preserved |
| Multi-branch same repo | `test-service-b` has main + develop | main got PR, develop got auto-merge |
| autoMerge=true | `test-service-b/develop` | Direct commit to develop branch |
| autoMerge=false | `test-service-b/main` | PR created |
| PR reuse (second run) | Bumped version again | Same PR updated, not new PR |
| @mentions | Last committer looked up via GitHub API | Shown in PR body and auto-merge commit |
| Jenkins pipeline | Full pipeline in local Jenkins (Docker) | Build + run succeeds, resolver finds correct versions |
| Up-to-date skipping | Run again with no version changes | 0 created, all skipped |

**Test repos created:**
- `francis007-del-01/core-lib` — trigger repo, version 3.0.0
- `francis007-del-01/utils` — trigger repo, version 2.0.0
- `francis007-del-01/test-service-b` — consumer, main + develop branches
- `francis007-del-01/test-service-c` — consumer, property-based version

### What was NOT tested
- Large pom files (>1000 lines)
- Multi-module projects (nested poms)
- Concurrent runs (two cron jobs at the same time)
- GitHub API rate limiting (>5000 calls/hour)
- Real email/Slack notifications
- Plugin-based version (Maven plugin approach was eliminated)
