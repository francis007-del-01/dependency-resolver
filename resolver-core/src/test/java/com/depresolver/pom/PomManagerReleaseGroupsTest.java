package com.depresolver.pom;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomManagerReleaseGroupsTest {

    private final PomManager mgr = new PomManager();

    @Test
    void listCoordinatesForTargetsScansAllSections() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>service</artifactId><version>1.0.0</version>
                  <properties>
                    <acme.lib.version>1.2.0</acme.lib.version>
                    <acme.plugin.version>3.1.0</acme.plugin.version>
                  </properties>
                  <dependencies>
                    <dependency><groupId>com.acme.libs</groupId><artifactId>core</artifactId><version>${acme.lib.version}</version></dependency>
                    <dependency><groupId>org.other</groupId><artifactId>ignore-me</artifactId><version>1.0.0</version></dependency>
                  </dependencies>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>com.acme.libs</groupId><artifactId>managed</artifactId><version>2.0.0</version></dependency>
                    </dependencies>
                  </dependencyManagement>
                  <build>
                    <plugins>
                      <plugin><groupId>com.acme.plugins</groupId><artifactId>build-tool</artifactId><version>${acme.plugin.version}</version></plugin>
                    </plugins>
                    <pluginManagement>
                      <plugins>
                        <plugin><groupId>com.acme.plugins</groupId><artifactId>managed-plugin</artifactId><version>4.0.0</version></plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                  <parent>
                    <groupId>com.acme.parent</groupId>
                    <artifactId>base-parent</artifactId>
                    <version>5.0.0</version>
                  </parent>
                </project>
                """;

        List<PomManager.PomCoordinates> coordinates = mgr.listCoordinatesForTargets(
                pom,
                List.of("com.acme.libs", "com.acme.plugins", "com.acme.parent"),
                java.util.Set.of());

        assertEquals(5, coordinates.size());
        assertTrue(coordinates.contains(new PomManager.PomCoordinates("com.acme.libs", "core", "1.2.0")));
        assertTrue(coordinates.contains(new PomManager.PomCoordinates("com.acme.libs", "managed", "2.0.0")));
        assertTrue(coordinates.contains(new PomManager.PomCoordinates("com.acme.plugins", "build-tool", "3.1.0")));
        assertTrue(coordinates.contains(new PomManager.PomCoordinates("com.acme.plugins", "managed-plugin", "4.0.0")));
        assertTrue(coordinates.contains(new PomManager.PomCoordinates("com.acme.parent", "base-parent", "5.0.0")));
    }

    @Test
    void listCoordinatesReturnsEmptyWhenNoGroupMatches() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>service</artifactId><version>1.0.0</version>
                  <dependencies>
                    <dependency><groupId>com.acme.libs</groupId><artifactId>core</artifactId><version>1.0.0</version></dependency>
                  </dependencies>
                </project>
                """;
        List<PomManager.PomCoordinates> coordinates = mgr.listCoordinatesForTargets(
                pom, List.of("org.missing"), java.util.Set.of());
        assertTrue(coordinates.isEmpty());
    }

    @Test
    void listCoordinatesForTargetsMatchesSpecificArtifact() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>service</artifactId><version>1.0.0</version>
                  <dependencies>
                    <dependency><groupId>com.intuit</groupId><artifactId>pymt-lib</artifactId><version>1.0.0</version></dependency>
                    <dependency><groupId>com.intuit</groupId><artifactId>pymt-schema</artifactId><version>1.0.0</version></dependency>
                  </dependencies>
                </project>
                """;
        List<PomManager.PomCoordinates> coordinates = mgr.listCoordinatesForTargets(
                pom, List.of(), java.util.Set.of("com.intuit:pymt-lib"));
        assertEquals(1, coordinates.size());
        assertEquals(new PomManager.PomCoordinates("com.intuit", "pymt-lib", "1.0.0"), coordinates.get(0));
    }

    @Test
    void findBumpsFromLatestVersionsSkipsWhenAlreadyLatest() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>service</artifactId><version>1.0.0</version>
                  <dependencies>
                    <dependency><groupId>com.acme.libs</groupId><artifactId>core</artifactId><version>1.2.0</version></dependency>
                  </dependencies>
                </project>
                """;
        var bumps = mgr.findBumpsFromLatestVersions(pom, Map.of("com.acme.libs:core", "1.2.0"));
        assertTrue(bumps.isEmpty());
    }
}
