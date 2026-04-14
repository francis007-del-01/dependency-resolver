package com.depresolver.pom;

import com.depresolver.scanner.DependencyMatch;
import com.depresolver.scanner.DependencyMatch.VersionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PomModifierTest {

    private PomModifier modifier;

    @BeforeEach
    void setUp() {
        modifier = new PomModifier();
    }

    @Test
    void updateDirectVersion() throws IOException {
        String xml = loadResource("sample-pom-direct-version.xml");

        DependencyMatch match = DependencyMatch.builder()
                .groupId("com.pool")
                .artifactId("pool")
                .currentVersion("1.0.0-SNAPSHOT")
                .versionType(VersionType.DIRECT)
                .build();

        String result = modifier.updateVersion(xml, match, "2.0.0");

        assertNotEquals(xml, result);
        assertTrue(result.contains("<version>2.0.0</version>"));
        assertFalse(result.contains("1.0.0-SNAPSHOT"));
        // Spring version should be untouched
        assertTrue(result.contains("<version>6.1.0</version>"));
    }

    @Test
    void updatePropertyVersion() throws IOException {
        String xml = loadResource("sample-pom-property-version.xml");

        DependencyMatch match = DependencyMatch.builder()
                .groupId("com.pool")
                .artifactId("pool")
                .currentVersion("1.0.0-SNAPSHOT")
                .versionType(VersionType.PROPERTY)
                .propertyKey("pool.version")
                .build();

        String result = modifier.updateVersion(xml, match, "2.0.0");

        assertNotEquals(xml, result);
        // Property value should be updated
        assertTrue(result.contains("<pool.version>2.0.0</pool.version>"));
        // The dependency element should still use the property reference
        assertTrue(result.contains("${pool.version}"));
        // Spring version property should be untouched
        assertTrue(result.contains("<spring.version>6.1.0</spring.version>"));
    }

    @Test
    void updateManagedVersion() throws IOException {
        String xml = loadResource("sample-pom-dep-management.xml");

        DependencyMatch match = DependencyMatch.builder()
                .groupId("com.pool")
                .artifactId("pool")
                .currentVersion("1.0.0-SNAPSHOT")
                .versionType(VersionType.MANAGED)
                .build();

        String result = modifier.updateVersion(xml, match, "2.0.0");

        assertNotEquals(xml, result);
        assertTrue(result.contains("<version>2.0.0</version>"));
        // slf4j version should be untouched
        assertTrue(result.contains("<version>2.0.11</version>"));
    }

    @Test
    void noChangeWhenAlreadyUpToDate() throws IOException {
        String xml = loadResource("sample-pom-direct-version.xml");

        DependencyMatch match = DependencyMatch.builder()
                .groupId("com.pool")
                .artifactId("pool")
                .currentVersion("1.0.0-SNAPSHOT")
                .versionType(VersionType.DIRECT)
                .build();

        String result = modifier.updateVersion(xml, match, "1.0.0-SNAPSHOT");
        assertEquals(xml, result);
    }

    @Test
    void preservesFormatting() throws IOException {
        String xml = loadResource("sample-pom-direct-version.xml");

        DependencyMatch match = DependencyMatch.builder()
                .groupId("com.pool")
                .artifactId("pool")
                .currentVersion("1.0.0-SNAPSHOT")
                .versionType(VersionType.DIRECT)
                .build();

        String result = modifier.updateVersion(xml, match, "2.0.0");

        // The only difference should be the version string itself
        String expected = xml.replace("1.0.0-SNAPSHOT", "2.0.0");
        assertEquals(expected, result);
    }

    @Test
    void updateParentVersion() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.pool</groupId>
                        <artifactId>pool-parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.11</version>
                        </dependency>
                    </dependencies>
                </project>
                """;

        DependencyMatch match = DependencyMatch.builder()
                .groupId("com.pool")
                .artifactId("pool-parent")
                .currentVersion("1.0.0")
                .versionType(VersionType.PARENT)
                .build();

        String result = modifier.updateVersion(xml, match, "2.0.0");

        assertNotEquals(xml, result);
        assertTrue(result.contains("<version>2.0.0</version>"));
        assertTrue(result.contains("<version>2.0.11</version>"));
    }

    @Test
    void updatePluginVersion() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>with-plugin</artifactId>
                    <version>1.0.0</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.internal</groupId>
                                <artifactId>our-plugin</artifactId>
                                <version>1.0.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.12.1</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;

        DependencyMatch match = DependencyMatch.builder()
                .groupId("com.internal")
                .artifactId("our-plugin")
                .currentVersion("1.0.0")
                .versionType(VersionType.PLUGIN)
                .build();

        String result = modifier.updateVersion(xml, match, "2.0.0");

        assertNotEquals(xml, result);
        assertTrue(result.contains("<artifactId>our-plugin</artifactId>\n                <version>2.0.0</version>"));
        assertTrue(result.contains("<version>3.12.1</version>"));
        assertFalse(result.contains("<artifactId>our-plugin</artifactId>\n                <version>1.0.0</version>"));
    }

    @Test
    void updateParentPreservesFormatting() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <parent>
                        <groupId>com.pool</groupId>
                        <artifactId>pool-parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>child</artifactId>
                </project>
                """;

        DependencyMatch match = DependencyMatch.builder()
                .groupId("com.pool")
                .artifactId("pool-parent")
                .currentVersion("1.0.0")
                .versionType(VersionType.PARENT)
                .build();

        String result = modifier.updateVersion(xml, match, "2.0.0");
        String expected = xml.replace("<version>1.0.0</version>", "<version>2.0.0</version>");
        assertEquals(expected, result);
    }

    private String loadResource(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/" + name));
    }
}
