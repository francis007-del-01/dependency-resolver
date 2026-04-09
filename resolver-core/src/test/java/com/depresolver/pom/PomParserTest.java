package com.depresolver.pom;

import com.depresolver.scanner.DependencyMatch;
import com.depresolver.scanner.DependencyMatch.VersionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PomParserTest {

    private PomParser parser;

    @BeforeEach
    void setUp() {
        parser = new PomParser();
    }

    @Test
    void parseDirectVersion() throws IOException {
        String xml = loadResource("sample-pom-direct-version.xml");
        PomParser.PomInfo info = parser.parse(xml);

        assertEquals("com.example", info.groupId());
        assertEquals("service-b", info.artifactId());
        assertEquals("1.0.0", info.version());
        assertEquals(2, info.dependencies().size());
        assertTrue(info.modules().isEmpty());

        PomParser.DependencyInfo poolDep = info.dependencies().stream()
                .filter(d -> "pool".equals(d.artifactId()))
                .findFirst().orElseThrow();
        assertEquals("com.pool", poolDep.groupId());
        assertEquals("1.0.0-SNAPSHOT", poolDep.resolvedVersion());
        assertEquals(VersionType.DIRECT, poolDep.versionType());
        assertNull(poolDep.propertyKey());
    }

    @Test
    void parsePropertyVersion() throws IOException {
        String xml = loadResource("sample-pom-property-version.xml");
        PomParser.PomInfo info = parser.parse(xml);

        assertEquals("com.example", info.groupId());
        assertEquals("service-c", info.artifactId());
        assertEquals("1.0.0-SNAPSHOT", info.properties().get("pool.version"));

        PomParser.DependencyInfo poolDep = info.dependencies().stream()
                .filter(d -> "pool".equals(d.artifactId()))
                .findFirst().orElseThrow();
        assertEquals("1.0.0-SNAPSHOT", poolDep.resolvedVersion());
        assertEquals(VersionType.PROPERTY, poolDep.versionType());
        assertEquals("pool.version", poolDep.propertyKey());
    }

    @Test
    void parseDependencyManagement() throws IOException {
        String xml = loadResource("sample-pom-dep-management.xml");
        PomParser.PomInfo info = parser.parse(xml);

        assertEquals("parent-project", info.artifactId());
        assertEquals(List.of("module-a", "module-b"), info.modules());
        assertEquals(1, info.managedDependencies().size());

        PomParser.DependencyInfo managed = info.managedDependencies().get(0);
        assertEquals("com.pool", managed.groupId());
        assertEquals("pool", managed.artifactId());
        assertEquals("1.0.0-SNAPSHOT", managed.resolvedVersion());
    }

    @Test
    void findDependencyMatchesDirect() throws IOException {
        String xml = loadResource("sample-pom-direct-version.xml");
        List<DependencyMatch> matches = parser.findDependencyMatches(xml, "com.pool", "pool", "myorg", "service-b", "pom.xml");

        assertEquals(1, matches.size());
        DependencyMatch match = matches.get(0);
        assertEquals("com.pool", match.getGroupId());
        assertEquals("pool", match.getArtifactId());
        assertEquals("1.0.0-SNAPSHOT", match.getCurrentVersion());
        assertEquals(VersionType.DIRECT, match.getVersionType());
        assertEquals("myorg", match.getRepoOwner());
        assertEquals("service-b", match.getRepoName());
    }

    @Test
    void findDependencyMatchesProperty() throws IOException {
        String xml = loadResource("sample-pom-property-version.xml");
        List<DependencyMatch> matches = parser.findDependencyMatches(xml, "com.pool", "pool", "myorg", "service-c", "pom.xml");

        assertEquals(1, matches.size());
        DependencyMatch match = matches.get(0);
        assertEquals(VersionType.PROPERTY, match.getVersionType());
        assertEquals("pool.version", match.getPropertyKey());
    }

    @Test
    void findDependencyMatchesNone() throws IOException {
        String xml = loadResource("sample-pom-direct-version.xml");
        List<DependencyMatch> matches = parser.findDependencyMatches(xml, "com.nonexistent", "nope", "org", "repo", "pom.xml");
        assertTrue(matches.isEmpty());
    }

    @Test
    void findDependencyMatchesManaged() throws IOException {
        String xml = loadResource("sample-pom-dep-management.xml");
        List<DependencyMatch> matches = parser.findDependencyMatches(xml, "com.pool", "pool", "myorg", "parent", "pom.xml");

        assertEquals(1, matches.size());
        assertEquals(VersionType.MANAGED, matches.get(0).getVersionType());
    }

    private String loadResource(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/" + name));
    }
}
