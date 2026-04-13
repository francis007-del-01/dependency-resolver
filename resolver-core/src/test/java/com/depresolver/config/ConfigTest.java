package com.depresolver.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @Test
    void deserializesConfig() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/config.yaml"));
        var config = new ObjectMapper(new YAMLFactory()).readValue(yaml, ResolverConfig.class);

        assertNotNull(config.getRepos());
        assertFalse(config.getRepos().isEmpty());
    }

    @Test
    void parseTriggerRepo() throws IOException {
        String yaml = """
            repos:
              - owner: myorg
                name: core-lib
                triggerBranch: master
            """;
        var config = new ObjectMapper(new YAMLFactory()).readValue(yaml, ResolverConfig.class);

        assertEquals(1, config.getRepos().size());
        var repo = config.getRepos().get(0);
        assertEquals("myorg", repo.getOwner());
        assertEquals("core-lib", repo.getName());
        assertEquals("master", repo.getTriggerBranch());
        assertNull(repo.getTargetBranches());
        assertEquals("pom.xml", repo.getPomPath());
    }

    @Test
    void parseTargetRepo() throws IOException {
        String yaml = """
            repos:
              - owner: myorg
                name: my-service
                targetBranches:
                  - name: main
                    autoMerge: false
                  - name: develop
                    autoMerge: true
            """;
        var config = new ObjectMapper(new YAMLFactory()).readValue(yaml, ResolverConfig.class);

        var repo = config.getRepos().get(0);
        assertNull(repo.getTriggerBranch());
        assertEquals(2, repo.getTargetBranches().size());

        var main = repo.getTargetBranches().get(0);
        assertEquals("main", main.getName());
        assertFalse(main.isAutoMerge());

        var develop = repo.getTargetBranches().get(1);
        assertEquals("develop", develop.getName());
        assertTrue(develop.isAutoMerge());
    }

    @Test
    void parseBothTriggerAndTarget() throws IOException {
        String yaml = """
            repos:
              - owner: myorg
                name: shared-lib
                triggerBranch: master
                targetBranches:
                  - name: master
                    autoMerge: false
            """;
        var config = new ObjectMapper(new YAMLFactory()).readValue(yaml, ResolverConfig.class);

        var repo = config.getRepos().get(0);
        assertEquals("master", repo.getTriggerBranch());
        assertEquals(1, repo.getTargetBranches().size());
    }

    @Test
    void customPomPath() throws IOException {
        String yaml = """
            repos:
              - owner: myorg
                name: multi-module
                pomPath: parent/pom.xml
                triggerBranch: master
            """;
        var config = new ObjectMapper(new YAMLFactory()).readValue(yaml, ResolverConfig.class);

        assertEquals("parent/pom.xml", config.getRepos().get(0).getPomPath());
    }
}
