package com.depresolver.pom;

import com.depresolver.scanner.DependencyMatch;
import com.depresolver.scanner.DependencyMatch.VersionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

public class PomParser {

    private static final Logger log = LoggerFactory.getLogger(PomParser.class);

    public record PomInfo(
            String groupId,
            String artifactId,
            String version,
            Map<String, String> properties,
            List<DependencyInfo> dependencies,
            List<DependencyInfo> managedDependencies,
            List<String> modules
    ) {}

    public record DependencyInfo(
            String groupId,
            String artifactId,
            String rawVersion,
            String resolvedVersion,
            VersionType versionType,
            String propertyKey
    ) {}

    public PomInfo parse(String xmlContent) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();

            String groupId = getDirectChildText(root, "groupId");
            String artifactId = getDirectChildText(root, "artifactId");
            String version = getDirectChildText(root, "version");

            // Inherit from parent if not declared
            Element parentEl = getDirectChildElement(root, "parent");
            if (parentEl != null) {
                if (groupId == null) groupId = getDirectChildText(parentEl, "groupId");
                if (version == null) version = getDirectChildText(parentEl, "version");
            }

            Map<String, String> properties = parseProperties(root);
            List<DependencyInfo> dependencies = parseDependencies(root, "dependencies", properties);
            List<DependencyInfo> managedDependencies = parseManagedDependencies(root, properties);
            List<String> modules = parseModules(root);

            return new PomInfo(groupId, artifactId, version, properties, dependencies, managedDependencies, modules);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse pom.xml", e);
        }
    }

    public List<DependencyMatch> findDependencyMatches(String xmlContent, String targetGroupId,
                                                        String targetArtifactId, String repoOwner,
                                                        String repoName, String pomPath) {
        PomInfo pomInfo = parse(xmlContent);
        List<DependencyMatch> matches = new ArrayList<>();

        // Check direct dependencies
        for (DependencyInfo dep : pomInfo.dependencies()) {
            if (targetGroupId.equals(dep.groupId()) && targetArtifactId.equals(dep.artifactId())) {
                matches.add(DependencyMatch.builder()
                        .groupId(dep.groupId())
                        .artifactId(dep.artifactId())
                        .currentVersion(dep.resolvedVersion())
                        .versionType(dep.versionType())
                        .propertyKey(dep.propertyKey())
                        .repoOwner(repoOwner)
                        .repoName(repoName)
                        .pomPath(pomPath)
                        .build());
            }
        }

        // Check managed dependencies
        for (DependencyInfo dep : pomInfo.managedDependencies()) {
            if (targetGroupId.equals(dep.groupId()) && targetArtifactId.equals(dep.artifactId())) {
                matches.add(DependencyMatch.builder()
                        .groupId(dep.groupId())
                        .artifactId(dep.artifactId())
                        .currentVersion(dep.resolvedVersion())
                        .versionType(dep.versionType() == VersionType.DIRECT ? VersionType.MANAGED : dep.versionType())
                        .propertyKey(dep.propertyKey())
                        .repoOwner(repoOwner)
                        .repoName(repoName)
                        .pomPath(pomPath)
                        .build());
            }
        }

        return matches;
    }

    private Map<String, String> parseProperties(Element root) {
        Map<String, String> properties = new HashMap<>();
        Element propsEl = getDirectChildElement(root, "properties");
        if (propsEl == null) return properties;

        NodeList children = propsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                properties.put(node.getNodeName(), node.getTextContent().trim());
            }
        }
        return properties;
    }

    private List<DependencyInfo> parseDependencies(Element root, String containerTag, Map<String, String> properties) {
        List<DependencyInfo> deps = new ArrayList<>();
        Element container = getDirectChildElement(root, containerTag);
        if (container == null) return deps;

        NodeList depNodes = container.getElementsByTagName("dependency");
        for (int i = 0; i < depNodes.getLength(); i++) {
            Element depEl = (Element) depNodes.item(i);
            // Only process direct children of this container
            if (!depEl.getParentNode().equals(container)) continue;

            DependencyInfo info = parseSingleDependency(depEl, properties);
            if (info != null) deps.add(info);
        }
        return deps;
    }

    private List<DependencyInfo> parseManagedDependencies(Element root, Map<String, String> properties) {
        List<DependencyInfo> deps = new ArrayList<>();
        Element dmEl = getDirectChildElement(root, "dependencyManagement");
        if (dmEl == null) return deps;

        Element depsEl = getDirectChildElement(dmEl, "dependencies");
        if (depsEl == null) return deps;

        NodeList depNodes = depsEl.getElementsByTagName("dependency");
        for (int i = 0; i < depNodes.getLength(); i++) {
            Element depEl = (Element) depNodes.item(i);
            DependencyInfo info = parseSingleDependency(depEl, properties);
            if (info != null) deps.add(info);
        }
        return deps;
    }

    private DependencyInfo parseSingleDependency(Element depEl, Map<String, String> properties) {
        String gId = getDirectChildText(depEl, "groupId");
        String aId = getDirectChildText(depEl, "artifactId");
        String rawVersion = getDirectChildText(depEl, "version");

        if (gId == null || aId == null) return null;

        VersionType type = VersionType.DIRECT;
        String propertyKey = null;
        String resolvedVersion = rawVersion;

        if (rawVersion != null && PropertyResolver.isPropertyReference(rawVersion)) {
            type = VersionType.PROPERTY;
            propertyKey = PropertyResolver.extractPropertyKey(rawVersion);
            resolvedVersion = PropertyResolver.resolve(rawVersion, properties);
        }

        return new DependencyInfo(gId, aId, rawVersion, resolvedVersion, type, propertyKey);
    }

    private List<String> parseModules(Element root) {
        List<String> modules = new ArrayList<>();
        Element modulesEl = getDirectChildElement(root, "modules");
        if (modulesEl == null) return modules;

        NodeList moduleNodes = modulesEl.getElementsByTagName("module");
        for (int i = 0; i < moduleNodes.getLength(); i++) {
            modules.add(moduleNodes.item(i).getTextContent().trim());
        }
        return modules;
    }

    private Element getDirectChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(tagName)) {
                return (Element) node;
            }
        }
        return null;
    }

    private String getDirectChildText(Element parent, String tagName) {
        Element child = getDirectChildElement(parent, tagName);
        return child != null ? child.getTextContent().trim() : null;
    }
}
