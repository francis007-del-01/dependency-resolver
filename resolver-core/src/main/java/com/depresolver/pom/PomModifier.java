package com.depresolver.pom;

import com.depresolver.scanner.DependencyMatch;
import com.depresolver.scanner.DependencyMatch.VersionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modifies pom.xml content using string-based replacement to preserve formatting.
 * Never uses DOM serialization which would destroy whitespace, comments, and ordering.
 */
public class PomModifier {

    private static final Logger log = LoggerFactory.getLogger(PomModifier.class);

    /**
     * Updates the version of a dependency in the raw pom.xml content.
     *
     * @param pomContent    raw XML string
     * @param match         the dependency match describing what to update
     * @param newVersion    the new version to set
     * @return modified pom.xml content, or the original if no change was made
     */
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

    private String  updatePropertyVersion(String pomContent, String propertyKey, String oldVersion, String newVersion) {
        // Match <propertyKey>oldVersion</propertyKey> inside <properties> block
        String escapedKey = Pattern.quote(propertyKey);
        String escapedOld = Pattern.quote(oldVersion);
        Pattern pattern = Pattern.compile(
                "(<%s>\\s*)%s(\\s*</%s>)".formatted(escapedKey, escapedOld, escapedKey)
        );

        Matcher matcher = pattern.matcher(pomContent);
        if (matcher.find()) {
            String result = matcher.replaceFirst("$1" + Matcher.quoteReplacement(newVersion) + "$2");
            log.info("Updated property {} from {} to {}", propertyKey, oldVersion, newVersion);
            return result;
        }

        log.warn("Could not find property {} with value {} in pom.xml", propertyKey, oldVersion);
        return pomContent;
    }

    private String updateDirectVersion(String pomContent, String groupId, String artifactId,
                                       String oldVersion, String newVersion) {
        // Match the <dependency> block containing the target groupId and artifactId, then replace version
        String escapedGroupId = Pattern.quote(groupId);
        String escapedArtifactId = Pattern.quote(artifactId);
        String escapedOldVersion = Pattern.quote(oldVersion);

        // Pattern matches a dependency block with groupId, artifactId in any order, then captures version
        // We use a two-pass approach: first find the dependency block, then replace version within it
        Pattern depBlockPattern = Pattern.compile(
                "<dependency>\\s*" +
                "(?=(?:(?!</dependency>).)*<groupId>\\s*" + escapedGroupId + "\\s*</groupId>)" +
                "(?=(?:(?!</dependency>).)*<artifactId>\\s*" + escapedArtifactId + "\\s*</artifactId>)" +
                "((?:(?!</dependency>).)*)" +
                "</dependency>",
                Pattern.DOTALL
        );

        Matcher blockMatcher = depBlockPattern.matcher(pomContent);
        if (blockMatcher.find()) {
            String block = blockMatcher.group(0);

            // Now replace the version within this specific block
            Pattern versionPattern = Pattern.compile(
                    "(<version>\\s*)" + escapedOldVersion + "(\\s*</version>)"
            );
            String updatedBlock = versionPattern.matcher(block).replaceFirst(
                    "$1" + Matcher.quoteReplacement(newVersion) + "$2"
            );

            String result = pomContent.substring(0, blockMatcher.start()) + updatedBlock + pomContent.substring(blockMatcher.end());
            log.info("Updated {}:{} from {} to {}", groupId, artifactId, oldVersion, newVersion);
            return result;
        }

        log.warn("Could not find dependency block for {}:{} with version {}", groupId, artifactId, oldVersion);
        return pomContent;
    }
}
