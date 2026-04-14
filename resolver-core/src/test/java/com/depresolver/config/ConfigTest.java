package com.depresolver.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test void deserializesFromFile() throws Exception {
        var config = yaml.readValue(getClass().getResourceAsStream("/config.yaml"), ResolverConfig.class);
        assertNotNull(config.getRepos());
        assertFalse(config.getRepos().isEmpty());
    }

    @Test void parsesOwnerAndNameFromUrl() throws Exception {
        var config = yaml.readValue("repos:\n  - url: https://github.com/myorg/core-lib\n    triggerBranch: master", ResolverConfig.class);
        var repo = config.getRepos().get(0);
        assertEquals("https://github.com/myorg/core-lib", repo.getUrl());
        assertEquals("myorg", repo.getOwner());
        assertEquals("core-lib", repo.getName());
        assertEquals("master", repo.getTriggerBranch());
    }

    @Test void handlesGitSuffix() throws Exception {
        var config = yaml.readValue("repos:\n  - url: https://github.com/myorg/core-lib.git\n    triggerBranch: master", ResolverConfig.class);
        assertEquals("core-lib", config.getRepos().get(0).getName());
    }

    @Test void parseTargetBranches() throws Exception {
        var config = yaml.readValue("repos:\n  - url: https://github.com/myorg/svc\n    targetBranches:\n      - name: main\n        autoMerge: false\n      - name: develop\n        autoMerge: true", ResolverConfig.class);
        var branches = config.getRepos().get(0).getTargetBranches();
        assertEquals(2, branches.size());
        assertFalse(branches.get(0).isAutoMerge());
        assertTrue(branches.get(1).isAutoMerge());
    }

    @Test void defaultPomPath() throws Exception {
        var config = yaml.readValue("repos:\n  - url: https://github.com/myorg/lib\n    triggerBranch: master", ResolverConfig.class);
        assertEquals("pom.xml", config.getRepos().get(0).getPomPath());
    }

    @Test void customPomPath() throws Exception {
        var config = yaml.readValue("repos:\n  - url: https://github.com/myorg/lib\n    pomPath: parent/pom.xml\n    triggerBranch: master", ResolverConfig.class);
        assertEquals("parent/pom.xml", config.getRepos().get(0).getPomPath());
    }
}
