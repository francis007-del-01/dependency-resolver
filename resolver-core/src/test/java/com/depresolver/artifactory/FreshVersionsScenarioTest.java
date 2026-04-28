package com.depresolver.artifactory;

import com.depresolver.pom.PomManager;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end scenario smoke test with fresh version numbers different from
 * ArtifactoryClientTest's fixtures. Simulates a realistic multi-dep resolve pass
 * and prints what the resolver decides, so a reader can eyeball the full flow.
 */
class FreshVersionsScenarioTest {

    private HttpServer server;
    private String baseUrl;
    private ArtifactoryClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new ArtifactoryClient(baseUrl, "rel", "snap", "tok", new PomManager(), new MavenMetadataParser());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test void threeDependencyScenarioWithDistinctVersions() throws Exception {
        // ---------------------------------------------------------------
        // Dep 1: release and SNAPSHOT built from SAME sha
        // com.acme.billing:core-lib  — release 2.0.0 / snapshot 2.1.0-SNAPSHOT
        // Expected resolver decision: use release (shortcut via SHA equality)
        // ---------------------------------------------------------------
        String sameSha = "1111aaaa2222bbbb3333cccc4444dddd55556666";

        routeMeta("/rel/com/acme/billing/core-lib/maven-metadata.xml",
                releaseMeta("2.0.0"));
        routeMeta("/snap/com/acme/billing/core-lib/maven-metadata.xml",
                snapshotMeta("2.1.0-SNAPSHOT"));
        routeMeta("/snap/com/acme/billing/core-lib/2.1.0-SNAPSHOT/maven-metadata.xml",
                snapshotVersionMeta("2.1.0-20260423.100000-1"));
        routeJar("/rel/com/acme/billing/core-lib/2.0.0/core-lib-2.0.0.jar",
                jarWithGitSha(sameSha));
        routeJar("/snap/com/acme/billing/core-lib/2.1.0-SNAPSHOT/core-lib-2.1.0-20260423.100000-1.jar",
                jarWithGitSha(sameSha));

        // ---------------------------------------------------------------
        // Dep 2: release and SNAPSHOT built from DIFFERENT shas
        // com.acme.payments:tx-engine — release 3.5.0 / snapshot 3.6.0-SNAPSHOT
        // SCM points to acme-payments/tx-engine on github.com
        // Expected resolver decision: need to delegate to GitHubClient.compareCommits
        //   (not exercised here — GitHub isn't mocked; we just show both SHAs + SCM
        //   are extractable, which is everything the resolver needs before handing
        //   off to GitHub).
        // ---------------------------------------------------------------
        String releaseSha = "aaaa1111bbbb2222cccc3333dddd4444eeee5555";
        String snapshotSha = "ffff9999eeee8888dddd7777cccc6666bbbb5555";

        routeMeta("/rel/com/acme/payments/tx-engine/maven-metadata.xml",
                releaseMeta("3.5.0"));
        routeMeta("/snap/com/acme/payments/tx-engine/maven-metadata.xml",
                snapshotMeta("3.6.0-SNAPSHOT"));
        routeMeta("/snap/com/acme/payments/tx-engine/3.6.0-SNAPSHOT/maven-metadata.xml",
                snapshotVersionMeta("3.6.0-20260423.144500-2"));
        routeJar("/rel/com/acme/payments/tx-engine/3.5.0/tx-engine-3.5.0.jar",
                jarWithGitShaAndScm(releaseSha,
                        "scm:git:https://github.com/acme-payments/tx-engine.git",
                        "com.acme.payments", "tx-engine"));
        routeJar("/snap/com/acme/payments/tx-engine/3.6.0-SNAPSHOT/tx-engine-3.6.0-20260423.144500-2.jar",
                jarWithGitSha(snapshotSha));

        // ---------------------------------------------------------------
        // Dep 3: release-only lookup, no SNAPSHOT work
        // org.example:stable-schema — release 5.2.1
        // Expected resolver decision: use release 5.2.1
        // ---------------------------------------------------------------
        routeMeta("/rel/org/example/stable-schema/maven-metadata.xml",
                releaseMeta("5.2.1"));

        // ------- Run the three resolutions -------
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  FRESH-VERSIONS SCENARIO — three dependencies, three paths");
        System.out.println("================================================================");

        // Dep 1
        System.out.println();
        System.out.println("Dep 1  com.acme.billing:core-lib  (release vs snapshot decision path)");
        Optional<String> d1rel = client.latestReleaseVersion("com.acme.billing", "core-lib");
        Optional<String> d1snap = client.latestSnapshotBaseVersion("com.acme.billing", "core-lib");
        Optional<ArtifactoryClient.GitInfo> d1relInfo = client.getReleaseGitInfo("com.acme.billing", "core-lib", d1rel.get());
        Optional<ArtifactoryClient.GitInfo> d1snapInfo = client.getSnapshotGitInfo("com.acme.billing", "core-lib", d1snap.get());
        System.out.printf("    release  = %s  sha=%s%n", d1rel.get(), abbrev(d1relInfo.get().commitSha()));
        System.out.printf("    snapshot = %s  sha=%s%n", d1snap.get(), abbrev(d1snapInfo.get().commitSha()));
        boolean sameBuildSource = d1relInfo.get().commitSha().equals(d1snapInfo.get().commitSha());
        System.out.printf("    same SHA? %s  →  decision: %s%n",
                sameBuildSource ? "yes" : "no",
                sameBuildSource ? "USE RELEASE (2.0.0)" : "fall through to commit-compare");
        assertTrue(sameBuildSource, "Dep 1 setup expects identical SHAs");

        // Dep 2
        System.out.println();
        System.out.println("Dep 2  com.acme.payments:tx-engine  (release vs snapshot decision path)");
        Optional<String> d2rel = client.latestReleaseVersion("com.acme.payments", "tx-engine");
        Optional<String> d2snap = client.latestSnapshotBaseVersion("com.acme.payments", "tx-engine");
        Optional<ArtifactoryClient.GitInfo> d2relInfo = client.getReleaseGitInfo("com.acme.payments", "tx-engine", d2rel.get());
        Optional<ArtifactoryClient.GitInfo> d2snapInfo = client.getSnapshotGitInfo("com.acme.payments", "tx-engine", d2snap.get());
        Optional<PomManager.GitHubCoords> d2scm = client.getReleaseScm("com.acme.payments", "tx-engine", d2rel.get());
        System.out.printf("    release  = %s  sha=%s%n", d2rel.get(), abbrev(d2relInfo.get().commitSha()));
        System.out.printf("    snapshot = %s  sha=%s%n", d2snap.get(), abbrev(d2snapInfo.get().commitSha()));
        System.out.printf("    SCM from release jar: %s/%s%n", d2scm.get().owner(), d2scm.get().name());
        System.out.printf("    SHAs differ → would call GitHub compare %s/%s %s...%s%n",
                d2scm.get().owner(), d2scm.get().name(),
                abbrev(d2relInfo.get().commitSha()), abbrev(d2snapInfo.get().commitSha()));
        System.out.println("    decision: DEFERRED TO GitHubClient (not mocked in this scenario)");
        assertNotEquals(d2relInfo.get().commitSha(), d2snapInfo.get().commitSha(), "Dep 2 setup expects different SHAs");
        assertEquals("acme-payments", d2scm.get().owner());
        assertEquals("tx-engine", d2scm.get().name());

        // Dep 3
        System.out.println();
        System.out.println("Dep 3  org.example:stable-schema  (release-only path)");
        Optional<String> d3rel = client.latestReleaseVersion("org.example", "stable-schema");
        System.out.printf("    release = %s  →  decision: USE RELEASE (%s)%n", d3rel.get(), d3rel.get());
        assertEquals(Optional.of("5.2.1"), d3rel);

        System.out.println();
        System.out.println("================================================================");
        System.out.println("  All three dependencies resolved end-to-end against fake");
        System.out.println("  Artifactory. Flow covered:");
        System.out.println("    - maven-metadata.xml parsing (release + snapshot)");
        System.out.println("    - snapshot timestamp resolution");
        System.out.println("    - jar fetch + git.properties extraction (release + snapshot)");
        System.out.println("    - embedded pom.xml extraction + <scm> → GitHubCoords");
        System.out.println("================================================================");
    }

    // ---------- wiring helpers ----------

    private void routeMeta(String path, String xml) {
        route(path, 200, xml.getBytes(StandardCharsets.UTF_8));
    }

    private void routeJar(String path, byte[] jar) {
        route(path, 200, jar);
    }

    private void route(String path, int status, byte[] body) {
        server.createContext(path, (HttpHandler) ex -> {
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private static String releaseMeta(String releaseVersion) {
        return """
                <?xml version="1.0"?>
                <metadata>
                  <versioning>
                    <release>%s</release>
                  </versioning>
                </metadata>
                """.formatted(releaseVersion);
    }

    private static String snapshotMeta(String snapshotVersion) {
        return """
                <?xml version="1.0"?>
                <metadata>
                  <versioning>
                    <versions>
                      <version>%s</version>
                    </versions>
                  </versioning>
                </metadata>
                """.formatted(snapshotVersion);
    }

    private static String snapshotVersionMeta(String timestampedValue) {
        return """
                <?xml version="1.0"?>
                <metadata>
                  <versioning>
                    <snapshotVersions>
                      <snapshotVersion><extension>jar</extension><value>%s</value></snapshotVersion>
                    </snapshotVersions>
                  </versioning>
                </metadata>
                """.formatted(timestampedValue);
    }

    private static byte[] jarWithGitSha(String sha) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("META-INF/git.properties"));
            zos.write(("git.commit.id=" + sha + "\ngit.dirty=false\n").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] jarWithGitShaAndScm(String sha, String scmConnection,
                                              String groupId, String artifactId) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("META-INF/git.properties"));
            zos.write(("git.commit.id=" + sha + "\ngit.dirty=false\n").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/maven/" + groupId + "/" + artifactId + "/pom.xml"));
            String pom = """
                    <?xml version="1.0"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>1.0</version>
                      <scm><connection>%s</connection></scm>
                    </project>
                    """.formatted(groupId, artifactId, scmConnection);
            zos.write(pom.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static String abbrev(String sha) {
        return sha == null ? "?" : sha.substring(0, 7);
    }
}
