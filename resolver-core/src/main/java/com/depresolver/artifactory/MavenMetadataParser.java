package com.depresolver.artifactory;

import com.depresolver.xml.SecureXmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.util.Optional;

public class MavenMetadataParser {

    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    public Optional<String> latestReleaseVersion(byte[] metadataXml) throws IOException {
        try {
            Document doc = SecureXmlParser.parse(metadataXml);
            NodeList versioning = doc.getElementsByTagName("versioning");
            if (versioning.getLength() == 0) return Optional.empty();
            String release = SecureXmlParser.textOfChild((Element) versioning.item(0), "release");
            if (release != null && !release.isBlank()) return Optional.of(release);
            String latest = SecureXmlParser.textOfChild((Element) versioning.item(0), "latest");
            if (latest != null && !latest.isBlank() && !latest.endsWith(SNAPSHOT_SUFFIX)) {
                return Optional.of(latest);
            }
            NodeList versions = doc.getElementsByTagName("version");
            String best = null;
            for (int i = 0; i < versions.getLength(); i++) {
                String v = versions.item(i).getTextContent();
                if (v != null && !v.endsWith(SNAPSHOT_SUFFIX)) best = v.trim();
            }
            return Optional.ofNullable(best);
        } catch (Exception e) {
            throw new IOException("Failed to parse release maven-metadata.xml: " + e.getMessage(), e);
        }
    }

    public Optional<String> latestSnapshotBaseVersion(byte[] metadataXml) throws IOException {
        try {
            Document doc = SecureXmlParser.parse(metadataXml);
            NodeList versions = doc.getElementsByTagName("version");
            String latest = null;
            for (int i = 0; i < versions.getLength(); i++) {
                String v = versions.item(i).getTextContent();
                if (v != null && v.endsWith(SNAPSHOT_SUFFIX)) latest = v.trim();
            }
            return Optional.ofNullable(latest);
        } catch (Exception e) {
            throw new IOException("Failed to parse snapshot maven-metadata.xml: " + e.getMessage(), e);
        }
    }

    public Optional<String> latestTimestampedJarVersion(byte[] metadataXml) throws IOException {
        try {
            Document doc = SecureXmlParser.parse(metadataXml);
            NodeList snapshotVersions = doc.getElementsByTagName("snapshotVersion");
            for (int i = 0; i < snapshotVersions.getLength(); i++) {
                Element sv = (Element) snapshotVersions.item(i);
                String extension = SecureXmlParser.textOfChild(sv, "extension");
                String classifier = SecureXmlParser.textOfChild(sv, "classifier");
                if ("jar".equals(extension) && (classifier == null || classifier.isEmpty())) {
                    String value = SecureXmlParser.textOfChild(sv, "value");
                    if (value != null && !value.isBlank()) return Optional.of(value);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new IOException("Failed to parse snapshot-level maven-metadata.xml: " + e.getMessage(), e);
        }
    }
}
