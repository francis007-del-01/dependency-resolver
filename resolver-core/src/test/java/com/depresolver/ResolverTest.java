package com.depresolver;

import com.depresolver.version.VersionComparator;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResolverTest {

    @Test
    void parsesDirectVersion() throws Exception {
        String xml = Files.readString(Path.of("src/test/resources/sample-pom-direct-version.xml"));
        Model model = new MavenXpp3Reader().read(new StringReader(xml));

        assertEquals("com.example", model.getGroupId());
        assertEquals("service-b", model.getArtifactId());
        assertEquals(2, model.getDependencies().size());

        Dependency pool = model.getDependencies().stream()
                .filter(d -> "pool".equals(d.getArtifactId())).findFirst().orElseThrow();
        assertEquals("1.0.0-SNAPSHOT", pool.getVersion());
    }

    @Test
    void parsesPropertyVersion() throws Exception {
        String xml = Files.readString(Path.of("src/test/resources/sample-pom-property-version.xml"));
        Model model = new MavenXpp3Reader().read(new StringReader(xml));

        assertEquals("1.0.0-SNAPSHOT", model.getProperties().getProperty("pool.version"));

        Dependency pool = model.getDependencies().stream()
                .filter(d -> "pool".equals(d.getArtifactId())).findFirst().orElseThrow();
        assertEquals("${pool.version}", pool.getVersion());

        // Resolve property
        String resolved = model.getProperties().getProperty("pool.version");
        assertEquals("1.0.0-SNAPSHOT", resolved);
        assertTrue(VersionComparator.isOlderThan(resolved, "2.0.0"));
    }

    @Test
    void updatesDirectVersion() throws Exception {
        String xml = Files.readString(Path.of("src/test/resources/sample-pom-direct-version.xml"));
        Model model = new MavenXpp3Reader().read(new StringReader(xml));

        Dependency pool = model.getDependencies().stream()
                .filter(d -> "pool".equals(d.getArtifactId())).findFirst().orElseThrow();
        pool.setVersion("2.0.0");

        StringWriter writer = new StringWriter();
        new MavenXpp3Writer().write(writer, model);
        String result = writer.toString();

        assertTrue(result.contains("<version>2.0.0</version>"));
    }

    @Test
    void updatesPropertyVersion() throws Exception {
        String xml = Files.readString(Path.of("src/test/resources/sample-pom-property-version.xml"));
        Model model = new MavenXpp3Reader().read(new StringReader(xml));

        model.getProperties().setProperty("pool.version", "2.0.0");

        StringWriter writer = new StringWriter();
        new MavenXpp3Writer().write(writer, model);
        String result = writer.toString();

        assertTrue(result.contains("<pool.version>2.0.0</pool.version>"));
        assertTrue(result.contains("${pool.version}"));
    }

    @Test
    void configDeserialization() throws Exception {
        String yaml = Files.readString(Path.of("src/test/resources/config.yaml"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var config = mapper.readValue(yaml, com.depresolver.config.ResolverConfig.class);

        assertFalse(config.getRepos().isEmpty());
        assertNotNull(config.getRepos().get(0).getUrl());
    }
}
