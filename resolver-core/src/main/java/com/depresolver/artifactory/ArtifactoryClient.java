package com.depresolver.artifactory;

import com.depresolver.pom.PomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ArtifactoryClient {

    private static final Logger log = LoggerFactory.getLogger(ArtifactoryClient.class);

    private static final String MAVEN_METADATA_FILE = "maven-metadata.xml";
    private static final String EMBEDDED_POM_PREFIX = "META-INF/maven/";
    private static final String EMBEDDED_POM_SUFFIX = "/pom.xml";
    private static final String GIT_PROPERTIES_ROOT = "git.properties";
    private static final String GIT_PROPERTIES_META_INF = "META-INF/git.properties";
    private static final String GIT_COMMIT_ID_KEY = "git.commit.id";
    private static final String GIT_DIRTY_KEY = "git.dirty";

    public static class ArtifactoryNotFoundException extends IOException {
        public ArtifactoryNotFoundException(String msg) { super(msg); }
    }

    public record GitInfo(String commitSha, boolean dirty) {}

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String releaseRepo;
    private final String snapshotRepo;
    private final String token;
    private final PomManager pomManager;
    private final MavenMetadataParser metadataParser;

    public ArtifactoryClient(String baseUrl, String releaseRepo, String snapshotRepo, String token,
                             PomManager pomManager, MavenMetadataParser metadataParser) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.releaseRepo = releaseRepo;
        this.snapshotRepo = snapshotRepo;
        this.token = token;
        this.pomManager = pomManager;
        this.metadataParser = metadataParser;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Optional<String> latestReleaseVersion(String groupId, String artifactId)
            throws IOException, InterruptedException {
        return latestVersion(groupId, artifactId, true);
    }

    public Optional<String> latestSnapshotBaseVersion(String groupId, String artifactId)
            throws IOException, InterruptedException {
        return latestVersion(groupId, artifactId, false);
    }

    private Optional<String> latestVersion(String groupId, String artifactId, boolean release)
            throws IOException, InterruptedException {
        String repo = release ? releaseRepo : snapshotRepo;
        String url = "%s/%s/%s/%s/%s".formatted(
                baseUrl, repo, groupIdPath(groupId), artifactId, MAVEN_METADATA_FILE);
        byte[] body = fetchBytes(url);
        if (body == null) return Optional.empty();
        return release ? metadataParser.latestReleaseVersion(body) : metadataParser.latestSnapshotBaseVersion(body);
    }

    public Optional<GitInfo> getReleaseGitInfo(
            String groupId, String artifactId, String releaseVersion) throws IOException, InterruptedException {
        return getGitInfo(groupId, artifactId, releaseVersion, true);
    }

    public Optional<GitInfo> getSnapshotGitInfo(
            String groupId, String artifactId, String baseVersion) throws IOException, InterruptedException {
        return getGitInfo(groupId, artifactId, baseVersion, false);
    }

    private Optional<GitInfo> getGitInfo(
            String groupId, String artifactId, String version, boolean release)
            throws IOException, InterruptedException {
        byte[] jar;
        if (release) {
            jar = fetchJar(groupId, artifactId, version, version, true);
        } else {
            Optional<String> tsOpt = resolveSnapshotTimestampedVersion(groupId, artifactId, version);
            if (tsOpt.isEmpty()) return Optional.empty();
            jar = fetchJar(groupId, artifactId, version, tsOpt.get(), false);
        }
        if (jar == null) return Optional.empty();
        return extractGitInfo(jar);
    }

    public Optional<PomManager.GitHubCoords> getReleaseScm(
            String groupId, String artifactId, String releaseVersion) throws IOException, InterruptedException {
        byte[] jar = fetchJar(groupId, artifactId, releaseVersion, releaseVersion, true);
        if (jar == null) return Optional.empty();
        Optional<byte[]> pom = extractEmbeddedPom(jar);
        if (pom.isEmpty()) return Optional.empty();
        String pomContent = new String(pom.get(), StandardCharsets.UTF_8);
        return pomManager.extractGitHubCoords(pomContent);
    }

    private static Optional<GitInfo> extractGitInfo(byte[] jar) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.equals(GIT_PROPERTIES_ROOT) && !name.equals(GIT_PROPERTIES_META_INF)) continue;
                Properties props = new Properties();
                props.load(zis);
                String sha = props.getProperty(GIT_COMMIT_ID_KEY);
                if (sha == null || sha.isBlank()) return Optional.empty();
                boolean dirty = Boolean.parseBoolean(props.getProperty(GIT_DIRTY_KEY, "false"));
                return Optional.of(new GitInfo(sha, dirty));
            }
        }
        return Optional.empty();
    }

    private static Optional<byte[]> extractEmbeddedPom(byte[] jar) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if (n.startsWith(EMBEDDED_POM_PREFIX) && n.endsWith(EMBEDDED_POM_SUFFIX)) {
                    return Optional.of(readAll(zis));
                }
            }
        }
        return Optional.empty();
    }

    private static byte[] readAll(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zis.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private byte[] fetchJar(String groupId, String artifactId, String dirVersion, String fileVersion, boolean release)
            throws IOException, InterruptedException {
        String repo = release ? releaseRepo : snapshotRepo;
        String url = "%s/%s/%s/%s/%s/%s-%s.jar".formatted(
                baseUrl, repo, groupIdPath(groupId), artifactId,
                dirVersion, artifactId, fileVersion);
        return fetchBytes(url);
    }

    private Optional<String> resolveSnapshotTimestampedVersion(String groupId, String artifactId, String baseVersion)
            throws IOException, InterruptedException {
        String url = "%s/%s/%s/%s/%s/%s".formatted(
                baseUrl, snapshotRepo, groupIdPath(groupId), artifactId, baseVersion, MAVEN_METADATA_FILE);
        byte[] body = fetchBytes(url);
        if (body == null) return Optional.empty();
        return metadataParser.latestTimestampedJarVersion(body);
    }

    private byte[] fetchBytes(String url) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<byte[]> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        if (status == 404) return null;
        if (status >= 400) {
            throw new IOException("Artifactory error %d for %s".formatted(status, url));
        }
        return resp.body();
    }

    private static String groupIdPath(String groupId) {
        return groupId.replace('.', '/');
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
