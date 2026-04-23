package com.depresolver.pom;

import com.depresolver.version.VersionComparator;
import com.depresolver.xml.SecureXmlParser;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class PomManager {

    private static final String TAG_GROUP_ID = "groupId";
    private static final String TAG_ARTIFACT_ID = "artifactId";
    private static final String TAG_VERSION = "version";
    private static final String TAG_DEPENDENCY = "dependency";
    private static final String TAG_FETCH_LATEST = "fetchLatest";
    private static final String TAG_FETCH_RELEASE = "fetchRelease";

    private static final String PROPERTY_REF_PREFIX = "${";
    private static final String PROPERTY_REF_SUFFIX = "}";

    public record PomCoordinates(String groupId, String artifactId, String version) {}

    public record FetchDirective(String groupId, String artifactId) {
        public String key() { return groupId + ":" + artifactId; }
    }

    public record FetchDirectives(List<FetchDirective> latest, List<FetchDirective> release) {}

    public record GitHubCoords(String owner, String name) {}

    private static final Pattern GITHUB_URL = Pattern.compile(
            "github(?:\\.[a-zA-Z0-9.-]+)?(?:\\.com)?[:/]([^/]+)/([^/.\\s]+?)(?:\\.git)?(?:/|$)");

    public Optional<GitHubCoords> extractGitHubCoords(String pomContent) {
        try {
            Document doc = SecureXmlParser.parse(pomContent);
            NodeList scmNodes = doc.getElementsByTagName("scm");
            if (scmNodes.getLength() == 0) return Optional.empty();
            Element scm = (Element) scmNodes.item(0);
            for (String field : new String[]{"url", "connection", "developerConnection"}) {
                Optional<GitHubCoords> coords = parseGitHubUrl(SecureXmlParser.textOfChild(scm, field));
                if (coords.isPresent()) return coords;
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<GitHubCoords> parseGitHubUrl(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String s = raw.trim()
                .replaceFirst("^scm:git:", "")
                .replaceFirst("^scm:", "");
        Matcher m = GITHUB_URL.matcher(s);
        if (!m.find()) return Optional.empty();
        return Optional.of(new GitHubCoords(m.group(1), m.group(2)));
    }

    public FetchDirectives readFetchDirectives(String pomContent) throws Exception {
        Document doc = SecureXmlParser.parse(pomContent);
        List<FetchDirective> latest = readDirectiveList(doc, TAG_FETCH_LATEST);
        List<FetchDirective> release = readDirectiveList(doc, TAG_FETCH_RELEASE);
        return new FetchDirectives(latest, release);
    }

    private List<FetchDirective> readDirectiveList(Document doc, String wrapperTag) {
        List<FetchDirective> out = new ArrayList<>();
        NodeList wrappers = doc.getElementsByTagName(wrapperTag);
        for (int i = 0; i < wrappers.getLength(); i++) {
            Element wrapper = (Element) wrappers.item(i);
            NodeList deps = wrapper.getElementsByTagName(TAG_DEPENDENCY);
            for (int j = 0; j < deps.getLength(); j++) {
                Element dep = (Element) deps.item(j);
                String g = SecureXmlParser.textOfChild(dep, TAG_GROUP_ID);
                String a = SecureXmlParser.textOfChild(dep, TAG_ARTIFACT_ID);
                if (g != null && a != null) out.add(new FetchDirective(g.trim(), a.trim()));
            }
        }
        return out;
    }

    public List<BumpedDependency> findBumpsFromDirectives(String pomContent,
                                                         Map<String, String> latestVersions,
                                                         Map<String, String> updatedByMap) throws Exception {
        Model model = parse(pomContent);
        Properties props = model.getProperties();
        List<BumpedDependency> bumps = new ArrayList<>();

        for (Dependency dep : model.getDependencies()) {
            checkDep(dep.getGroupId(), dep.getArtifactId(), resolveVersion(dep.getVersion(), props),
                    latestVersions, updatedByMap, bumps);
        }
        if (model.getDependencyManagement() != null) {
            for (Dependency dep : model.getDependencyManagement().getDependencies()) {
                checkDep(dep.getGroupId(), dep.getArtifactId(), resolveVersion(dep.getVersion(), props),
                        latestVersions, updatedByMap, bumps);
            }
        }
        if (model.getBuild() != null) {
            for (Plugin plugin : model.getBuild().getPlugins()) {
                checkDep(plugin.getGroupId(), plugin.getArtifactId(), resolveVersion(plugin.getVersion(), props),
                        latestVersions, updatedByMap, bumps);
            }
            if (model.getBuild().getPluginManagement() != null) {
                for (Plugin plugin : model.getBuild().getPluginManagement().getPlugins()) {
                    checkDep(plugin.getGroupId(), plugin.getArtifactId(), resolveVersion(plugin.getVersion(), props),
                            latestVersions, updatedByMap, bumps);
                }
            }
        }
        Parent parent = model.getParent();
        if (parent != null) {
            checkDep(parent.getGroupId(), parent.getArtifactId(), parent.getVersion(),
                    latestVersions, updatedByMap, bumps);
        }
        return bumps;
    }

    public String applyBumps(String pomContent, List<BumpedDependency> bumps) throws Exception {
        Document doc = SecureXmlParser.parse(pomContent);
        Map<String, String> properties = readProperties(doc);
        String updated = pomContent;

        for (BumpedDependency bump : bumps) {
            updated = applyOne(updated, doc, bump, properties);
        }
        return updated;
    }

    private String applyOne(String pom, Document doc, BumpedDependency bump, Map<String, String> properties) {
        String g = bump.groupId();
        String a = bump.artifactId();
        String newVer = bump.newVersion();

        for (String wrapper : new String[]{"dependencies", "dependencyManagement", "plugins", "pluginManagement"}) {
            NodeList wrappers = doc.getElementsByTagName(wrapper);
            for (int i = 0; i < wrappers.getLength(); i++) {
                Element w = (Element) wrappers.item(i);
                String childTag = wrapper.startsWith("dep") ? "dependency" : "plugin";
                NodeList nodes = w.getElementsByTagName(childTag);
                for (int j = 0; j < nodes.getLength(); j++) {
                    Element node = (Element) nodes.item(j);
                    if (!g.equals(SecureXmlParser.textOfChild(node, TAG_GROUP_ID))) continue;
                    if (!a.equals(SecureXmlParser.textOfChild(node, TAG_ARTIFACT_ID))) continue;
                    String rawVer = SecureXmlParser.textOfChild(node, TAG_VERSION);
                    if (rawVer == null) continue;
                    if (isPropertyRef(rawVer)) {
                        String key = extractPropertyKey(rawVer);
                        String current = properties.get(key);
                        if (current != null && !current.equals(newVer)) {
                            pom = replacePropertyValue(pom, key, current, newVer);
                            properties.put(key, newVer);
                        }
                    } else if (!rawVer.equals(newVer)) {
                        pom = replaceVersionInBlock(pom, node, rawVer, newVer);
                    }
                }
            }
        }

        NodeList parentNodes = doc.getElementsByTagName("parent");
        for (int i = 0; i < parentNodes.getLength(); i++) {
            Element p = (Element) parentNodes.item(i);
            if (p.getParentNode() != doc.getDocumentElement()) continue;
            if (!g.equals(SecureXmlParser.textOfChild(p, TAG_GROUP_ID))) continue;
            if (!a.equals(SecureXmlParser.textOfChild(p, TAG_ARTIFACT_ID))) continue;
            String rawVer = SecureXmlParser.textOfChild(p, TAG_VERSION);
            if (rawVer != null && !rawVer.equals(newVer)) {
                pom = replaceVersionInBlock(pom, p, rawVer, newVer);
            }
        }
        return pom;
    }

    private static Map<String, String> readProperties(Document doc) {
        Map<String, String> out = new java.util.HashMap<>();
        NodeList props = doc.getElementsByTagName("properties");
        for (int i = 0; i < props.getLength(); i++) {
            Element p = (Element) props.item(i);
            NodeList kids = p.getChildNodes();
            for (int j = 0; j < kids.getLength(); j++) {
                if (kids.item(j) instanceof Element el) {
                    out.put(el.getTagName(), el.getTextContent() == null ? "" : el.getTextContent().trim());
                }
            }
        }
        return out;
    }

    private static String replacePropertyValue(String pom, String key, String oldVal, String newVal) {
        String open = "<" + key + ">";
        String close = "</" + key + ">";
        int start = pom.indexOf(open);
        while (start >= 0) {
            int valStart = start + open.length();
            int end = pom.indexOf(close, valStart);
            if (end < 0) break;
            String between = pom.substring(valStart, end);
            if (between.trim().equals(oldVal)) {
                return pom.substring(0, valStart) + between.replace(oldVal, newVal) + pom.substring(end);
            }
            start = pom.indexOf(open, end);
        }
        return pom;
    }

    private static String replaceVersionInBlock(String pom, Element block, String oldVer, String newVer) {
        String gid = SecureXmlParser.textOfChild(block, TAG_GROUP_ID);
        String aid = SecureXmlParser.textOfChild(block, TAG_ARTIFACT_ID);
        if (gid == null || aid == null) return pom;
        int pos = 0;
        while (pos < pom.length()) {
            int gIdx = pom.indexOf("<groupId>" + gid + "</groupId>", pos);
            if (gIdx < 0) return pom;
            int aIdx = pom.indexOf("<artifactId>" + aid + "</artifactId>", gIdx);
            if (aIdx < 0) return pom;
            int vOpen = pom.indexOf("<version>", aIdx);
            if (vOpen < 0) return pom;
            int vClose = pom.indexOf("</version>", vOpen);
            if (vClose < 0) return pom;
            String between = pom.substring(vOpen + "<version>".length(), vClose);
            if (between.trim().equals(oldVer)) {
                return pom.substring(0, vOpen) + "<version>" + newVer + "</version>" + pom.substring(vClose + "</version>".length());
            }
            pos = vClose + 1;
        }
        return pom;
    }

    private void checkDep(String groupId, String artifactId, String currentVersion,
                          Map<String, String> latestVersions, Map<String, String> updatedByMap,
                          List<BumpedDependency> bumps) {
        if (groupId == null || artifactId == null || currentVersion == null) return;
        String key = groupId + ":" + artifactId;
        String latestVersion = latestVersions.get(key);
        if (latestVersion != null && VersionComparator.isOlderThan(currentVersion, latestVersion)) {
            String updatedBy = updatedByMap.getOrDefault(key, "unknown");
            bumps.add(new BumpedDependency(groupId, artifactId, currentVersion, latestVersion, updatedBy));
        }
    }

    private String resolveVersion(String version, Properties props) {
        if (version == null) return null;
        if (isPropertyRef(version)) {
            return props.getProperty(extractPropertyKey(version), version);
        }
        return version;
    }

    private boolean isPropertyRef(String value) {
        return value != null && value.startsWith(PROPERTY_REF_PREFIX) && value.endsWith(PROPERTY_REF_SUFFIX);
    }

    private String extractPropertyKey(String value) {
        return value.substring(PROPERTY_REF_PREFIX.length(), value.length() - PROPERTY_REF_SUFFIX.length());
    }

    private Model parse(String pomContent) throws Exception {
        return new MavenXpp3Reader().read(new StringReader(pomContent), false);
    }

}
