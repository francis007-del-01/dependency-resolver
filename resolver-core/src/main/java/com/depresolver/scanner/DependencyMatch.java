package com.depresolver.scanner;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DependencyMatch {

    public enum VersionType {
        DIRECT,
        PROPERTY,
        MANAGED,
        PARENT,
        PLUGIN
    }

    private String groupId;
    private String artifactId;
    private String currentVersion;
    private VersionType versionType;
    private String propertyKey;
}
