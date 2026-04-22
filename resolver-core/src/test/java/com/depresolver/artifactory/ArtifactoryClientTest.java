package com.depresolver.artifactory;

import com.sun.net.httpserver.HttpExchange;
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

class ArtifactoryClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void routeText(String path, int status, String body) {
        route(path, status, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    private void route(String path, int status, byte[] body) {
        server.createContext(path, (HttpHandler) ex -> {
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private ArtifactoryClient client() {
        return new ArtifactoryClient(baseUrl, "rel", "snap", "tok");
    }

    private static byte[] jarWithGitSha(String sha, boolean dirty) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("META-INF/git.properties"));
            String content = "git.commit.id=" + sha + "\ngit.dirty=" + dirty + "\n";
            zos.write(content.getBytes(StandardCharsets.UTF_8));
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

    @Test void latestReleaseVersionReadsReleaseElement() throws Exception {
        routeText("/rel/com/intuit/pymt-lib/maven-metadata.xml", 200, """
                <?xml version="1.0"?>
                <metadata>
                  <versioning>
                    <latest>1.0.444.0-SNAPSHOT</latest>
                    <release>1.0.443.0</release>
                  </versioning>
                </metadata>
                """);
        Optional<String> v = client().latestReleaseVersion("com.intuit", "pymt-lib");
        assertEquals(Optional.of("1.0.443.0"), v);
    }

    @Test void latestReleaseReturnsEmptyOn404() throws Exception {
        routeText("/rel/com/x/miss/maven-metadata.xml", 404, "not found");
        assertTrue(client().latestReleaseVersion("com.x", "miss").isEmpty());
    }

    @Test void latestSnapshotReturnsLatestSnapshotVersion() throws Exception {
        routeText("/snap/com/intuit/pymt-lib/maven-metadata.xml", 200, """
                <?xml version="1.0"?>
                <metadata>
                  <versioning>
                    <versions>
                      <version>1.0.443.0-SNAPSHOT</version>
                      <version>1.0.444.0-SNAPSHOT</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        assertEquals(Optional.of("1.0.444.0-SNAPSHOT"),
                client().latestSnapshotBaseVersion("com.intuit", "pymt-lib"));
    }

    @Test void serverErrorThrows() {
        routeText("/rel/com/x/boom/maven-metadata.xml", 500, "boom");
        assertThrows(IOException.class, () -> client().latestReleaseVersion("com.x", "boom"));
    }

    @Test void getReleaseGitInfoReadsShaFromReleaseJar() throws Exception {
        byte[] jar = jarWithGitSha("abc1234567890abcdef1234567890abcdef12345", false);
        route("/rel/com/intuit/pymt-lib/1.0.443.0/pymt-lib-1.0.443.0.jar", 200, jar);

        Optional<GitPropertiesExtractor.GitInfo> info =
                client().getReleaseGitInfo("com.intuit", "pymt-lib", "1.0.443.0");
        assertTrue(info.isPresent());
        assertEquals("abc1234567890abcdef1234567890abcdef12345", info.get().commitSha());
    }

    @Test void getSnapshotGitInfoResolvesTimestampedJarAndReadsSha() throws Exception {
        routeText("/snap/com/intuit/pymt-lib/1.0.444.0-SNAPSHOT/maven-metadata.xml", 200, """
                <?xml version="1.0"?>
                <metadata>
                  <version>1.0.444.0-SNAPSHOT</version>
                  <versioning>
                    <snapshot>
                      <timestamp>20260420.143547</timestamp>
                      <buildNumber>42</buildNumber>
                    </snapshot>
                    <snapshotVersions>
                      <snapshotVersion>
                        <extension>jar</extension>
                        <value>1.0.444.0-20260420.143547-42</value>
                      </snapshotVersion>
                    </snapshotVersions>
                  </versioning>
                </metadata>
                """);
        byte[] jar = jarWithGitSha("def4567890abcdef1234567890abcdef12345678", false);
        route("/snap/com/intuit/pymt-lib/1.0.444.0-SNAPSHOT/pymt-lib-1.0.444.0-20260420.143547-42.jar", 200, jar);

        Optional<GitPropertiesExtractor.GitInfo> info =
                client().getSnapshotGitInfo("com.intuit", "pymt-lib", "1.0.444.0-SNAPSHOT");
        assertTrue(info.isPresent());
        assertEquals("def4567890abcdef1234567890abcdef12345678", info.get().commitSha());
    }

    @Test void getSnapshotGitInfoReturnsEmptyWhenMetadataMissing() throws Exception {
        routeText("/snap/com/x/miss/1.0.0-SNAPSHOT/maven-metadata.xml", 404, "");
        assertTrue(client().getSnapshotGitInfo("com.x", "miss", "1.0.0-SNAPSHOT").isEmpty());
    }

    @Test void getReleaseGitInfoReturnsEmptyOn404() throws Exception {
        route("/rel/com/x/miss/1.0/miss-1.0.jar", 404, new byte[0]);
        assertTrue(client().getReleaseGitInfo("com.x", "miss", "1.0").isEmpty());
    }

    @Test void getReleaseScmReadsFromEmbeddedPom() throws Exception {
        byte[] jar = jarWithGitShaAndScm(
                "abc1234567890abcdef1234567890abcdef12345",
                "scm:git:https://github.com/myorg/core-lib.git",
                "com.intuit", "pymt-lib");
        route("/rel/com/intuit/pymt-lib/1.0.443.0/pymt-lib-1.0.443.0.jar", 200, jar);

        Optional<JarScmExtractor.GitHubCoords> c =
                client().getReleaseScm("com.intuit", "pymt-lib", "1.0.443.0");
        assertTrue(c.isPresent());
        assertEquals("myorg", c.get().owner());
        assertEquals("core-lib", c.get().name());
    }

    @Test void snapshotGitInfoIsCachedAcrossCalls() throws Exception {
        routeText("/snap/com/intuit/cached/1.0.0-SNAPSHOT/maven-metadata.xml", 200, """
                <?xml version="1.0"?>
                <metadata>
                  <version>1.0.0-SNAPSHOT</version>
                  <versioning>
                    <snapshotVersions>
                      <snapshotVersion><extension>jar</extension><value>1.0.0-20260420.100000-1</value></snapshotVersion>
                    </snapshotVersions>
                  </versioning>
                </metadata>
                """);
        byte[] jar = jarWithGitSha("cache7890abcdef1234567890abcdef1234567890", false);
        route("/snap/com/intuit/cached/1.0.0-SNAPSHOT/cached-1.0.0-20260420.100000-1.jar", 200, jar);

        ArtifactoryClient c = client();
        var first = c.getSnapshotGitInfo("com.intuit", "cached", "1.0.0-SNAPSHOT");
        var second = c.getSnapshotGitInfo("com.intuit", "cached", "1.0.0-SNAPSHOT");
        assertEquals(first, second);
    }
}
