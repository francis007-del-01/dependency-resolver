package com.depresolver.pom;

import com.depresolver.scanner.DependencyMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modifies pom.xml content using DOM-guided string replacement to preserve formatting.
 * Uses indexOf to locate exact positions, then replaces only the version value.
 * Never serializes DOM — original formatting, comments, and whitespace are untouched.
 */
public class PomModifier {

    private static final Logger log = LoggerFactory.getLogger(PomModifier.class);

    public String updateVersion(String pomContent, DependencyMatch match, String newVersion) {
        if (match.getCurrentVersion() != null && match.getCurrentVersion().equals(newVersion)) {
            log.info("Version already up to date: {}", newVersion);
            return pomContent;
        }

        return switch (match.getVersionType()) {
            case PROPERTY -> updatePropertyVersion(pomContent, match.getPropertyKey(), match.getCurrentVersion(), newVersion);
            case DIRECT, MANAGED -> updateDirectVersion(pomContent, match.getGroupId(), match.getArtifactId(), match.getCurrentVersion(), newVersion);
        };
    }

    private String updatePropertyVersion(String pomContent, String propertyKey, String oldVersion, String newVersion) {
        // Find <propertyKey>oldVersion</propertyKey>
        String openTag = "<" + propertyKey + ">";
        String closeTag = "</" + propertyKey + ">";

        int tagStart = pomContent.indexOf(openTag);
        if (tagStart == -1) {
            log.warn("Could not find property tag <{}> in pom.xml", propertyKey);
            return pomContent;
        }

        int valueStart = tagStart + openTag.length();
        int closeTagPos = pomContent.indexOf(closeTag, valueStart);
        if (closeTagPos == -1) {
            log.warn("Could not find closing tag for <{}>", propertyKey);
            return pomContent;
        }

        // Extract current value (may have whitespace)
        String currentValue = pomContent.substring(valueStart, closeTagPos);
        if (!currentValue.trim().equals(oldVersion)) {
            log.warn("Property {} has value '{}', expected '{}'", propertyKey, currentValue.trim(), oldVersion);
            return pomContent;
        }

        // Replace only the version value, preserving surrounding whitespace
        String newValue = currentValue.replace(oldVersion, newVersion);
        String result = pomContent.substring(0, valueStart) + newValue + pomContent.substring(closeTagPos);
        log.info("Updated property {} from {} to {}", propertyKey, oldVersion, newVersion);
        return result;
    }

    private String updateDirectVersion(String pomContent, String groupId, String artifactId,
                                       String oldVersion, String newVersion) {
        // Find the <dependency> block that contains both groupId and artifactId
        int searchFrom = 0;
        while (true) {
            int blockStart = pomContent.indexOf("<dependency>", searchFrom);
            if (blockStart == -1) break;

            int blockEnd = pomContent.indexOf("</dependency>", blockStart);
            if (blockEnd == -1) break;
            blockEnd += "</dependency>".length();

            String block = pomContent.substring(blockStart, blockEnd);

            // Check if this block contains our groupId and artifactId
            boolean hasGroupId = block.contains("<groupId>" + groupId + "</groupId>")
                    || block.contains("<groupId> " + groupId + " </groupId>")
                    || blockContainsTag(block, "groupId", groupId);
            boolean hasArtifactId = block.contains("<artifactId>" + artifactId + "</artifactId>")
                    || block.contains("<artifactId> " + artifactId + " </artifactId>")
                    || blockContainsTag(block, "artifactId", artifactId);

            if (hasGroupId && hasArtifactId) {
                // Found the right dependency block — now find <version>oldVersion</version> within it
                int versionTagStart = block.indexOf("<version>");
                if (versionTagStart == -1) {
                    log.warn("No <version> tag in dependency block for {}:{}", groupId, artifactId);
                    return pomContent;
                }

                int versionValueStart = versionTagStart + "<version>".length();
                int versionCloseTag = block.indexOf("</version>", versionValueStart);
                if (versionCloseTag == -1) {
                    log.warn("No closing </version> tag for {}:{}", groupId, artifactId);
                    return pomContent;
                }

                String currentVersion = block.substring(versionValueStart, versionCloseTag);
                if (!currentVersion.trim().equals(oldVersion)) {
                    searchFrom = blockEnd;
                    continue;
                }

                // Replace the version value at the exact position in the full content
                int absoluteVersionStart = blockStart + versionValueStart;
                int absoluteVersionEnd = blockStart + versionCloseTag;
                String newValue = currentVersion.replace(oldVersion, newVersion);

                String result = pomContent.substring(0, absoluteVersionStart) + newValue + pomContent.substring(absoluteVersionEnd);
                log.info("Updated {}:{} from {} to {}", groupId, artifactId, oldVersion, newVersion);
                return result;
            }

            searchFrom = blockEnd;
        }

        log.warn("Could not find dependency block for {}:{} with version {}", groupId, artifactId, oldVersion);
        return pomContent;
    }

    private boolean blockContainsTag(String block, String tagName, String value) {
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int start = block.indexOf(openTag);
        if (start == -1) return false;
        int end = block.indexOf(closeTag, start);
        if (end == -1) return false;
        String content = block.substring(start + openTag.length(), end).trim();
        return content.equals(value);
    }
}
