package com.depresolver.pom;

import com.depresolver.version.VersionComparator;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class PomManager {

    public record PomCoordinates(String groupId, String artifactId, String version) {}

    public record FetchDirective(String groupId, String artifactId) {
        public String key() { return groupId + ":" + artifactId; }
    }

    public record FetchDirectives(List<FetchDirective> latest, List<FetchDirective> release) {}

    public PomCoordinates readCoordinates(String pomContent) throws Exception {
        Model model = parse(pomContent);
        String groupId = model.getGroupId() != null ? model.getGroupId()
                : (model.getParent() != null ? model.getParent().getGroupId() : null);
        String version = model.getVersion() != null ? model.getVersion()
                : (model.getParent() != null ? model.getParent().getVersion() : null);
        return new PomCoordinates(groupId, model.getArtifactId(), version);
    }

    public FetchDirectives readFetchDirectives(String pomContent) throws Exception {
        Document doc = parseXmlDom(pomContent);
        List<FetchDirective> latest = readDirectiveList(doc, "fetchLatest");
        List<FetchDirective> release = readDirectiveList(doc, "fetchRelease");
        return new FetchDirectives(latest, release);
    }

    private List<FetchDirective> readDirectiveList(Document doc, String wrapperTag) {
        List<FetchDirective> out = new ArrayList<>();
        NodeList wrappers = doc.getElementsByTagName(wrapperTag);
        for (int i = 0; i < wrappers.getLength(); i++) {
            Element wrapper = (Element) wrappers.item(i);
            NodeList deps = wrapper.getElementsByTagName("dependency");
            for (int j = 0; j < deps.getLength(); j++) {
                Element dep = (Element) deps.item(j);
                String g = textOfChild(dep, "groupId");
                String a = textOfChild(dep, "artifactId");
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
        Document doc = parseXmlDom(pomContent);
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
                    if (!g.equals(textOfChild(node, "groupId"))) continue;
                    if (!a.equals(textOfChild(node, "artifactId"))) continue;
                    String rawVer = textOfChild(node, "version");
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
            if (!g.equals(textOfChild(p, "groupId"))) continue;
            if (!a.equals(textOfChild(p, "artifactId"))) continue;
            String rawVer = textOfChild(p, "version");
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
        String gid = textOfChild(block, "groupId");
        String aid = textOfChild(block, "artifactId");
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
        return value != null && value.startsWith("${") && value.endsWith("}");
    }

    private String extractPropertyKey(String value) {
        return value.substring(2, value.length() - 1);
    }

    private Model parse(String pomContent) throws Exception {
        return new MavenXpp3Reader().read(new StringReader(pomContent), false);
    }

    private static Document parseXmlDom(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String textOfChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        if (children.getLength() == 0) return null;
        return children.item(0).getTextContent();
    }
}
