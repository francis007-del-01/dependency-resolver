package com.depresolver.scanner;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DependencyMatch {

    public enum VersionType {
        DIRECT,
        PROPERTY,
        MANAGED
    }

    private String groupId;
    private String artifactId;
    private String currentVersion;
    private VersionType versionType;
    private String propertyKey;  // non-null only when versionType == PROPERTY

    // Which repo/pom this match was found in
    private String repoOwner;
    private String repoName;
    private String pomPath;
}
