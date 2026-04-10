package com.depresolver;

import com.depresolver.pom.PomModifier;
import com.depresolver.pom.PomParser;
import com.depresolver.registry.ArtifactEntry;
import com.depresolver.registry.VersionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResolverTest {

    private PomParser pomParser;
    private PomModifier pomModifier;

    @BeforeEach
    void setUp() {
        pomParser = new PomParser();
        pomModifier = new PomModifier();
    }

    @Test
    void detectsOutdatedDependency() throws IOException {
        String serviceBPom = Files.readString(Path.of("src/test/resources/sample-pom-direct-version.xml"));
        PomParser.PomInfo info = pomParser.parse(serviceBPom);

        PomParser.DependencyInfo poolDep = info.dependencies().stream()
                .filter(d -> "pool".equals(d.artifactId()))
                .findFirst().orElseThrow();

        assertEquals("1.0.0-SNAPSHOT", poolDep.resolvedVersion());
        assertNotEquals("2.0.0", poolDep.resolvedVersion());
    }

    @Test
    void updatesPropertyBasedVersion() throws IOException {
        String serviceCPom = Files.readString(Path.of("src/test/resources/sample-pom-property-version.xml"));

        PomParser.PomInfo info = pomParser.parse(serviceCPom);
        PomParser.DependencyInfo poolDep = info.dependencies().stream()
                .filter(d -> "pool".equals(d.artifactId()))
                .findFirst().orElseThrow();

        assertEquals("1.0.0-SNAPSHOT", poolDep.resolvedVersion());
        assertEquals("pool.version", poolDep.propertyKey());

        var match = com.depresolver.scanner.DependencyMatch.builder()
                .groupId("com.pool").artifactId("pool").currentVersion("1.0.0-SNAPSHOT")
                .versionType(com.depresolver.scanner.DependencyMatch.VersionType.PROPERTY)
                .propertyKey("pool.version").repoOwner("myorg").repoName("service-c").pomPath("pom.xml")
                .build();

        String updated = pomModifier.updateVersion(serviceCPom, match, "2.0.0");
        assertNotEquals(serviceCPom, updated);
        assertTrue(updated.contains("<pool.version>2.0.0</pool.version>"));
        assertTrue(updated.contains("${pool.version}"));
    }

    @Test
    void skipsUpToDateDependencies() throws IOException {
        String serviceBPom = Files.readString(Path.of("src/test/resources/sample-pom-direct-version.xml"));
        PomParser.PomInfo info = pomParser.parse(serviceBPom);

        PomParser.DependencyInfo springDep = info.dependencies().stream()
                .filter(d -> "spring-core".equals(d.artifactId()))
                .findFirst().orElseThrow();

        assertEquals("6.1.0", springDep.resolvedVersion());
    }

    @Test
    void batchesMultipleBumpsIntoPom() throws IOException {
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>multi-dep</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.pool</groupId>
                            <artifactId>pool</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>service-b</artifactId>
                            <version>0.9.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;

        var match1 = com.depresolver.scanner.DependencyMatch.builder()
                .groupId("com.pool").artifactId("pool").currentVersion("1.0.0")
                .versionType(com.depresolver.scanner.DependencyMatch.VersionType.DIRECT)
                .repoOwner("myorg").repoName("multi-dep").pomPath("pom.xml")
                .build();
        var match2 = com.depresolver.scanner.DependencyMatch.builder()
                .groupId("com.example").artifactId("service-b").currentVersion("0.9.0")
                .versionType(com.depresolver.scanner.DependencyMatch.VersionType.DIRECT)
                .repoOwner("myorg").repoName("multi-dep").pomPath("pom.xml")
                .build();

        String updated = pomModifier.updateVersion(pomContent, match1, "2.0.0");
        updated = pomModifier.updateVersion(updated, match2, "1.0.0");

        assertTrue(updated.contains("<version>2.0.0</version>"));
        assertTrue(updated.contains("<version>1.0.0</version>"));
        assertFalse(updated.contains("<version>0.9.0</version>"));
    }

    @Test
    void sharedPropertyBumpRecordsOnlyOnce() {
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>shared-prop</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <pool.version>1.0.0</pool.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.pool</groupId>
                            <artifactId>pool-core</artifactId>
                            <version>${pool.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>com.pool</groupId>
                            <artifactId>pool-api</artifactId>
                            <version>${pool.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """;

        var match1 = com.depresolver.scanner.DependencyMatch.builder()
                .groupId("com.pool").artifactId("pool-core").currentVersion("1.0.0")
                .versionType(com.depresolver.scanner.DependencyMatch.VersionType.PROPERTY)
                .propertyKey("pool.version").repoOwner("myorg").repoName("shared-prop").pomPath("pom.xml")
                .build();
        var match2 = com.depresolver.scanner.DependencyMatch.builder()
                .groupId("com.pool").artifactId("pool-api").currentVersion("1.0.0")
                .versionType(com.depresolver.scanner.DependencyMatch.VersionType.PROPERTY)
                .propertyKey("pool.version").repoOwner("myorg").repoName("shared-prop").pomPath("pom.xml")
                .build();

        List<String> recordedBumps = new ArrayList<>();

        String updated = pomModifier.updateVersion(pomContent, match1, "2.0.0");
        if (!updated.equals(pomContent)) {
            recordedBumps.add("pool-core");
        }

        String updated2 = pomModifier.updateVersion(updated, match2, "2.0.0");
        if (!updated2.equals(updated)) {
            recordedBumps.add("pool-api");
        }

        assertTrue(updated.contains("<pool.version>2.0.0</pool.version>"));
        assertEquals(1, recordedBumps.size(), "Only one bump should be recorded when both deps share a property");
        assertEquals("pool-core", recordedBumps.get(0));
    }

    @Test
    void registryDeserialization() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/sample-registry.yaml"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        VersionRegistry registry = mapper.readValue(yaml, VersionRegistry.class);

        assertEquals(3, registry.getArtifacts().size());
        ArtifactEntry pool = registry.getArtifacts().get(0);
        assertEquals("com.pool", pool.getGroupId());
        assertEquals("2.0.0", pool.getLatestVersion());
        assertEquals("myorg", pool.getRepoOwner());
        assertEquals("pool", pool.getRepoName());
        assertEquals("pom.xml", pool.getPomPath());
    }
}
