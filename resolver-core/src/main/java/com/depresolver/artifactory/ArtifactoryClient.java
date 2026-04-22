package com.depresolver.artifactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class ArtifactoryClient {

    private static final Logger log = LoggerFactory.getLogger(ArtifactoryClient.class);

    public static class ArtifactoryNotFoundException extends IOException {
        public ArtifactoryNotFoundException(String msg) { super(msg); }
    }

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String releaseRepo;
    private final String snapshotRepo;
    private final String token;

    private final ConcurrentHashMap<String, GitPropertiesExtractor.GitInfo> gitInfoCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JarScmExtractor.GitHubCoords> scmCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> jarCache = new ConcurrentHashMap<>();

    public ArtifactoryClient(String baseUrl, String releaseRepo, String snapshotRepo, String token) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.releaseRepo = releaseRepo;
        this.snapshotRepo = snapshotRepo;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Optional<String> latestReleaseVersion(String groupId, String artifactId)
            throws IOException, InterruptedException {
        String url = "%s/%s/%s/%s/maven-metadata.xml".formatted(
                baseUrl, releaseRepo, groupIdPath(groupId), artifactId);
        byte[] body = fetchBytes(url);
        if (body == null) return Optional.empty();
        return parseReleaseVersion(body);
    }

    public Optional<String> latestSnapshotBaseVersion(String groupId, String artifactId)
            throws IOException, InterruptedException {
        String url = "%s/%s/%s/%s/maven-metadata.xml".formatted(
                baseUrl, snapshotRepo, groupIdPath(groupId), artifactId);
        byte[] body = fetchBytes(url);
        if (body == null) return Optional.empty();
        return parseLatestSnapshotBase(body);
    }

    public Optional<GitPropertiesExtractor.GitInfo> getReleaseGitInfo(
            String groupId, String artifactId, String releaseVersion) throws IOException, InterruptedException {
        byte[] jar = fetchReleaseJar(groupId, artifactId, releaseVersion);
        if (jar == null) return Optional.empty();
        String cacheKey = "rel:" + groupId + ":" + artifactId + ":" + releaseVersion;
        GitPropertiesExtractor.GitInfo cached = gitInfoCache.get(cacheKey);
        if (cached != null) return Optional.of(cached);
        Optional<GitPropertiesExtractor.GitInfo> info = GitPropertiesExtractor.extract(jar);
        info.ifPresent(g -> gitInfoCache.put(cacheKey, g));
        return info;
    }

    public Optional<GitPropertiesExtractor.GitInfo> getSnapshotGitInfo(
            String groupId, String artifactId, String baseVersion) throws IOException, InterruptedException {
        Optional<String> tsOpt = resolveSnapshotTimestampedVersion(groupId, artifactId, baseVersion);
        if (tsOpt.isEmpty()) return Optional.empty();
        byte[] jar = fetchSnapshotJar(groupId, artifactId, baseVersion, tsOpt.get());
        if (jar == null) return Optional.empty();
        String cacheKey = "snap:" + groupId + ":" + artifactId + ":" + tsOpt.get();
        GitPropertiesExtractor.GitInfo cached = gitInfoCache.get(cacheKey);
        if (cached != null) return Optional.of(cached);
        Optional<GitPropertiesExtractor.GitInfo> info = GitPropertiesExtractor.extract(jar);
        info.ifPresent(g -> gitInfoCache.put(cacheKey, g));
        return info;
    }

    public Optional<JarScmExtractor.GitHubCoords> getReleaseScm(
            String groupId, String artifactId, String releaseVersion) throws IOException, InterruptedException {
        String cacheKey = "rel:" + groupId + ":" + artifactId + ":" + releaseVersion;
        JarScmExtractor.GitHubCoords cached = scmCache.get(cacheKey);
        if (cached != null) return Optional.of(cached);
        byte[] jar = fetchReleaseJar(groupId, artifactId, releaseVersion);
        if (jar == null) return Optional.empty();
        Optional<JarScmExtractor.GitHubCoords> coords = JarScmExtractor.extract(jar);
        coords.ifPresent(c -> scmCache.put(cacheKey, c));
        return coords;
    }

    private byte[] fetchReleaseJar(String groupId, String artifactId, String releaseVersion)
            throws IOException, InterruptedException {
        String cacheKey = "rel:" + groupId + ":" + artifactId + ":" + releaseVersion;
        byte[] cached = jarCache.get(cacheKey);
        if (cached != null) return cached;
        String url = "%s/%s/%s/%s/%s/%s-%s.jar".formatted(
                baseUrl, releaseRepo, groupIdPath(groupId), artifactId,
                releaseVersion, artifactId, releaseVersion);
        byte[] jar = fetchBytes(url);
        if (jar != null) jarCache.put(cacheKey, jar);
        return jar;
    }

    private byte[] fetchSnapshotJar(String groupId, String artifactId, String baseVersion, String tsVersion)
            throws IOException, InterruptedException {
        String cacheKey = "snap:" + groupId + ":" + artifactId + ":" + tsVersion;
        byte[] cached = jarCache.get(cacheKey);
        if (cached != null) return cached;
        String url = "%s/%s/%s/%s/%s/%s-%s.jar".formatted(
                baseUrl, snapshotRepo, groupIdPath(groupId), artifactId,
                baseVersion, artifactId, tsVersion);
        byte[] jar = fetchBytes(url);
        if (jar != null) jarCache.put(cacheKey, jar);
        return jar;
    }

    private Optional<String> resolveSnapshotTimestampedVersion(String groupId, String artifactId, String baseVersion)
            throws IOException, InterruptedException {
        String url = "%s/%s/%s/%s/%s/maven-metadata.xml".formatted(
                baseUrl, snapshotRepo, groupIdPath(groupId), artifactId, baseVersion);
        byte[] body = fetchBytes(url);
        if (body == null) return Optional.empty();
        return parseLatestTimestampedVersion(body, baseVersion);
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

    private static Optional<String> parseReleaseVersion(byte[] metadataXml) throws IOException {
        try {
            Document doc = parseXml(metadataXml);
            NodeList versioning = doc.getElementsByTagName("versioning");
            if (versioning.getLength() == 0) return Optional.empty();
            String release = textOfChild((Element) versioning.item(0), "release");
            if (release != null && !release.isBlank()) return Optional.of(release);
            String latest = textOfChild((Element) versioning.item(0), "latest");
            if (latest != null && !latest.isBlank() && !latest.endsWith("-SNAPSHOT")) {
                return Optional.of(latest);
            }
            NodeList versions = doc.getElementsByTagName("version");
            String best = null;
            for (int i = 0; i < versions.getLength(); i++) {
                String v = versions.item(i).getTextContent();
                if (v != null && !v.endsWith("-SNAPSHOT")) best = v.trim();
            }
            return Optional.ofNullable(best);
        } catch (Exception e) {
            throw new IOException("Failed to parse release maven-metadata.xml: " + e.getMessage(), e);
        }
    }

    private static Optional<String> parseLatestSnapshotBase(byte[] metadataXml) throws IOException {
        try {
            Document doc = parseXml(metadataXml);
            NodeList versions = doc.getElementsByTagName("version");
            String latest = null;
            for (int i = 0; i < versions.getLength(); i++) {
                String v = versions.item(i).getTextContent();
                if (v != null && v.endsWith("-SNAPSHOT")) latest = v.trim();
            }
            return Optional.ofNullable(latest);
        } catch (Exception e) {
            throw new IOException("Failed to parse snapshot maven-metadata.xml: " + e.getMessage(), e);
        }
    }

    private static Optional<String> parseLatestTimestampedVersion(byte[] metadataXml, String baseVersion) throws IOException {
        try {
            Document doc = parseXml(metadataXml);
            NodeList snapshotVersions = doc.getElementsByTagName("snapshotVersion");
            for (int i = 0; i < snapshotVersions.getLength(); i++) {
                Element sv = (Element) snapshotVersions.item(i);
                String extension = textOfChild(sv, "extension");
                String classifier = textOfChild(sv, "classifier");
                if ("jar".equals(extension) && (classifier == null || classifier.isEmpty())) {
                    String value = textOfChild(sv, "value");
                    if (value != null && !value.isBlank()) return Optional.of(value);
                }
            }
            NodeList snapshot = doc.getElementsByTagName("snapshot");
            if (snapshot.getLength() > 0) {
                Element s = (Element) snapshot.item(0);
                String timestamp = textOfChild(s, "timestamp");
                String buildNumber = textOfChild(s, "buildNumber");
                if (timestamp != null && buildNumber != null) {
                    String baseNoSnap = baseVersion.replace("-SNAPSHOT", "");
                    return Optional.of(baseNoSnap + "-" + timestamp + "-" + buildNumber);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new IOException("Failed to parse snapshot-level maven-metadata.xml: " + e.getMessage(), e);
        }
    }

    private static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml));
    }

    private static String textOfChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        if (children.getLength() == 0) return null;
        Node first = children.item(0);
        return first.getTextContent() != null ? first.getTextContent().trim() : null;
    }

    private static String groupIdPath(String groupId) {
        return groupId.replace('.', '/');
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
