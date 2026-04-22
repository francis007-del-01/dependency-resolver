package com.depresolver.pom;

import com.depresolver.pom.PomManager.FetchDirective;
import com.depresolver.pom.PomManager.FetchDirectives;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PomManagerFetchDirectivesTest {

    private final PomManager mgr = new PomManager();

    @Test void emptyWhenNoDirectives() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>c</groupId><artifactId>svc</artifactId><version>1.0</version>
                </project>
                """;
        FetchDirectives d = mgr.readFetchDirectives(pom);
        assertTrue(d.latest().isEmpty());
        assertTrue(d.release().isEmpty());
    }

    @Test void parsesFetchLatestAndFetchRelease() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>c</groupId><artifactId>svc</artifactId><version>1.0</version>
                  <fetchLatest>
                    <dependency><groupId>com.intuit</groupId><artifactId>pymt-lib</artifactId></dependency>
                    <dependency><groupId>com.intuit</groupId><artifactId>pymt-async</artifactId></dependency>
                  </fetchLatest>
                  <fetchRelease>
                    <dependency><groupId>com.intuit</groupId><artifactId>pymt-schema</artifactId></dependency>
                  </fetchRelease>
                </project>
                """;
        FetchDirectives d = mgr.readFetchDirectives(pom);
        assertEquals(2, d.latest().size());
        assertEquals(new FetchDirective("com.intuit", "pymt-lib"), d.latest().get(0));
        assertEquals(new FetchDirective("com.intuit", "pymt-async"), d.latest().get(1));
        assertEquals(1, d.release().size());
        assertEquals(new FetchDirective("com.intuit", "pymt-schema"), d.release().get(0));
    }

    @Test void findBumpsFromDirectivesUpgradesDirectDep() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>c</groupId><artifactId>svc</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.intuit</groupId><artifactId>pymt-lib</artifactId><version>1.0.443.0</version></dependency>
                  </dependencies>
                </project>
                """;
        var latest = java.util.Map.of("com.intuit:pymt-lib", "1.0.444.0-SNAPSHOT");
        var updatedBy = java.util.Map.<String, String>of();
        var bumps = mgr.findBumpsFromDirectives(pom, latest, updatedBy);
        assertEquals(1, bumps.size());
        assertEquals("1.0.443.0", bumps.get(0).oldVersion());
        assertEquals("1.0.444.0-SNAPSHOT", bumps.get(0).newVersion());
    }

    @Test void findBumpsSkipsWhenAlreadyLatest() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>c</groupId><artifactId>svc</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.intuit</groupId><artifactId>pymt-lib</artifactId><version>1.0.444.0</version></dependency>
                  </dependencies>
                </project>
                """;
        var latest = java.util.Map.of("com.intuit:pymt-lib", "1.0.444.0");
        var bumps = mgr.findBumpsFromDirectives(pom, latest, java.util.Map.of());
        assertTrue(bumps.isEmpty());
    }
}
