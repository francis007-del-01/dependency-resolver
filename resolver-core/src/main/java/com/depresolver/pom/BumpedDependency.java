package com.depresolver.pom;

public record BumpedDependency(String groupId, String artifactId, String oldVersion, String newVersion) {
}
