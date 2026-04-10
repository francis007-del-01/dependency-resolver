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

    @Test
    void unresolvedProjectVersionPropertyReturnsNull() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sibling-user</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>sibling-lib</artifactId>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        PomParser.PomInfo info = parser.parse(xml);
        PomParser.DependencyInfo dep = info.dependencies().get(0);

        assertEquals(VersionType.PROPERTY, dep.versionType());
        assertEquals("project.version", dep.propertyKey());
        assertNull(dep.resolvedVersion());
    }

    @Test
    void nestedPropertyReferenceReturnsNull() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>nested-prop</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <lib.version>${pool.version}</lib.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.pool</groupId>
                            <artifactId>pool</artifactId>
                            <version>${lib.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        PomParser.PomInfo info = parser.parse(xml);
        PomParser.DependencyInfo dep = info.dependencies().get(0);

        assertEquals(VersionType.PROPERTY, dep.versionType());
        assertEquals("lib.version", dep.propertyKey());
        assertNull(dep.resolvedVersion());
    }

    @Test
    void parseParentDependency() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.pool</groupId>
                        <artifactId>pool-parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child-module</artifactId>
                </project>
                """;
        PomParser.PomInfo info = parser.parse(xml);

        assertNotNull(info.parentDependency());
        assertEquals("com.pool", info.parentDependency().groupId());
        assertEquals("pool-parent", info.parentDependency().artifactId());
        assertEquals("1.0.0", info.parentDependency().resolvedVersion());
        assertEquals(VersionType.DIRECT, info.parentDependency().versionType());
    }

    @Test
    void parseParentNullWhenAbsent() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>standalone</artifactId>
                    <version>1.0.0</version>
                </project>
                """;
        PomParser.PomInfo info = parser.parse(xml);
        assertNull(info.parentDependency());
    }

    @Test
    void parsePlugins() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>with-plugins</artifactId>
                    <version>1.0.0</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.12.1</version>
                            </plugin>
                            <plugin>
                                <groupId>com.internal</groupId>
                                <artifactId>our-plugin</artifactId>
                                <version>1.0.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;
        PomParser.PomInfo info = parser.parse(xml);

        assertEquals(2, info.plugins().size());
        assertEquals("maven-compiler-plugin", info.plugins().get(0).artifactId());
        assertEquals("3.12.1", info.plugins().get(0).resolvedVersion());
        assertEquals("our-plugin", info.plugins().get(1).artifactId());
        assertEquals("1.0.0", info.plugins().get(1).resolvedVersion());
    }

    @Test
    void parseManagedPlugins() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>with-managed-plugins</artifactId>
                    <version>1.0.0</version>
                    <build>
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <groupId>com.internal</groupId>
                                    <artifactId>managed-plugin</artifactId>
                                    <version>2.0.0</version>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                    </build>
                </project>
                """;
        PomParser.PomInfo info = parser.parse(xml);

        assertEquals(1, info.managedPlugins().size());
        assertEquals("managed-plugin", info.managedPlugins().get(0).artifactId());
        assertEquals("2.0.0", info.managedPlugins().get(0).resolvedVersion());
        assertTrue(info.plugins().isEmpty());
    }

    @Test
    void parsePluginWithPropertyVersion() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>prop-plugin</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <compiler.version>3.12.1</compiler.version>
                    </properties>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>${compiler.version}</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;
        PomParser.PomInfo info = parser.parse(xml);

        assertEquals(1, info.plugins().size());
        PomParser.DependencyInfo plugin = info.plugins().get(0);
        assertEquals(VersionType.PROPERTY, plugin.versionType());
        assertEquals("compiler.version", plugin.propertyKey());
        assertEquals("3.12.1", plugin.resolvedVersion());
    }

    @Test
    void pluginsWithoutVersionAreSkipped() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>no-version-plugin</artifactId>
                    <version>1.0.0</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;
        PomParser.PomInfo info = parser.parse(xml);
        assertTrue(info.plugins().isEmpty());
    }

    private String loadResource(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/" + name));
    }
}
