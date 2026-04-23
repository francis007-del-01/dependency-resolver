package com.depresolver.pom;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PomManagerGitHubCoordsTest {

    private final PomManager pm = new PomManager();

    private static String pomWithScm(String scmBody) {
        return """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>c</groupId><artifactId>lib</artifactId><version>1.0</version>
                  <scm>
                %s
                  </scm>
                </project>
                """.formatted(scmBody);
    }

    @Test void parsesGithubComConnection() {
        Optional<PomManager.GitHubCoords> c = pm.extractGitHubCoords(
                pomWithScm("    <connection>scm:git:https://github.com/myorg/core-lib.git</connection>"));
        assertTrue(c.isPresent());
        assertEquals("myorg", c.get().owner());
        assertEquals("core-lib", c.get().name());
    }

    @Test void parsesGithubEnterpriseHost() {
        Optional<PomManager.GitHubCoords> c = pm.extractGitHubCoords(
                pomWithScm("    <connection>scm:git:https://github.intuit.com/billingcomm-custpayments/pymt-lib.git</connection>"));
        assertTrue(c.isPresent());
        assertEquals("billingcomm-custpayments", c.get().owner());
        assertEquals("pymt-lib", c.get().name());
    }

    @Test void parsesSshStyleUrl() {
        Optional<PomManager.GitHubCoords> c = pm.extractGitHubCoords(
                pomWithScm("    <connection>git@github.com:myorg/core-lib.git</connection>"));
        assertTrue(c.isPresent());
        assertEquals("myorg", c.get().owner());
        assertEquals("core-lib", c.get().name());
    }

    @Test void emptyForNonGithubUrl() {
        assertTrue(pm.extractGitHubCoords(
                pomWithScm("    <connection>https://gitlab.com/myorg/lib.git</connection>")).isEmpty());
    }

    @Test void emptyWhenNoScmSection() {
        String pom = """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>c</groupId><artifactId>lib</artifactId><version>1.0</version>
                </project>
                """;
        assertTrue(pm.extractGitHubCoords(pom).isEmpty());
    }

    @Test void prefersUrlOverConnectionIfUrlIsGithub() {
        Optional<PomManager.GitHubCoords> c = pm.extractGitHubCoords(
                pomWithScm("""
                            <url>https://github.com/a/b</url>
                            <connection>scm:git:https://gitlab.com/x/y.git</connection>"""));
        assertTrue(c.isPresent());
        assertEquals("a", c.get().owner());
        assertEquals("b", c.get().name());
    }
}
