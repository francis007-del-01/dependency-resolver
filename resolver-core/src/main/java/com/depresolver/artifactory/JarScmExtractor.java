package com.depresolver.artifactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class JarScmExtractor {

    public record GitHubCoords(String owner, String name) {}

    private static final Pattern GITHUB_URL = Pattern.compile(
            "github(?:\\.[a-zA-Z0-9.-]+)?(?:\\.com)?[:/]([^/]+)/([^/.\\s]+?)(?:\\.git)?(?:/|$)");

    private JarScmExtractor() {}

    public static Optional<GitHubCoords> extract(byte[] jarBytes) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String n = entry.getName();
                if (!n.startsWith("META-INF/maven/") || !n.endsWith("/pom.xml")) continue;
                byte[] pom = readAll(zis);
                Optional<GitHubCoords> coords = parseScm(pom);
                if (coords.isPresent()) return coords;
            }
        }
        return Optional.empty();
    }

    private static Optional<GitHubCoords> parseScm(byte[] pomBytes) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(pomBytes));

            NodeList scmNodes = doc.getElementsByTagName("scm");
            if (scmNodes.getLength() == 0) return Optional.empty();
            Element scm = (Element) scmNodes.item(0);

            for (String field : new String[] {"url", "connection", "developerConnection"}) {
                String val = textOfChild(scm, field);
                Optional<GitHubCoords> coords = parseGitHubUrl(val);
                if (coords.isPresent()) return coords;
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Optional<GitHubCoords> parseGitHubUrl(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String s = raw.trim()
                .replaceFirst("^scm:git:", "")
                .replaceFirst("^scm:", "");
        Matcher m = GITHUB_URL.matcher(s);
        if (!m.find()) return Optional.empty();
        return Optional.of(new GitHubCoords(m.group(1), m.group(2)));
    }

    private static String textOfChild(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        String t = nl.item(0).getTextContent();
        return t == null ? null : t.trim();
    }

    private static byte[] readAll(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zis.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }
}
