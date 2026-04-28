package com.depresolver.pom;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomManagerReleaseGroupsTest {

    private final PomManager mgr = new PomManager();

    @Test
    void listCoordinatesForGroupIdsScansAllSections() throws Exception {
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

        List<PomManager.PomCoordinates> coordinates = mgr.listCoordinatesForGroupIds(
                pom,
                List.of("com.acme.libs", "com.acme.plugins", "com.acme.parent"));

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
        List<PomManager.PomCoordinates> coordinates = mgr.listCoordinatesForGroupIds(pom, List.of("org.missing"));
        assertTrue(coordinates.isEmpty());
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
