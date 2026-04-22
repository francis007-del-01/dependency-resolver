package com.depresolver.artifactory;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class JarScmExtractorTest {

    private static byte[] buildJar(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    @Test void parsesGithubComConnection() {
        var c = JarScmExtractor.parseGitHubUrl("scm:git:https://github.com/myorg/core-lib.git");
        assertTrue(c.isPresent());
        assertEquals("myorg", c.get().owner());
        assertEquals("core-lib", c.get().name());
    }

    @Test void parsesGithubEnterpriseHost() {
        var c = JarScmExtractor.parseGitHubUrl("scm:git:https://github.intuit.com/billingcomm-custpayments/pymt-lib.git");
        assertTrue(c.isPresent());
        assertEquals("billingcomm-custpayments", c.get().owner());
        assertEquals("pymt-lib", c.get().name());
    }

    @Test void parsesSshStyleUrl() {
        var c = JarScmExtractor.parseGitHubUrl("git@github.com:myorg/core-lib.git");
        assertTrue(c.isPresent());
        assertEquals("myorg", c.get().owner());
        assertEquals("core-lib", c.get().name());
    }

    @Test void emptyForNonGithubUrl() {
        assertTrue(JarScmExtractor.parseGitHubUrl("https://gitlab.com/myorg/lib.git").isEmpty());
    }

    @Test void emptyWhenNoScmSection() throws Exception {
        String pom = """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>c</groupId><artifactId>lib</artifactId><version>1.0</version>
                </project>
                """;
        byte[] jar = buildJar(Map.of("META-INF/maven/c/lib/pom.xml", pom));
        assertTrue(JarScmExtractor.extract(jar).isEmpty());
    }

    @Test void extractsFromJarEmbeddedPom() throws Exception {
        String pom = """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.intuit.payment.common</groupId>
                  <artifactId>pymt-lib</artifactId>
                  <version>1.0.443.0</version>
                  <scm>
                    <connection>scm:git:https://github.intuit.com/billingcomm-custpayments/pymt-lib.git</connection>
                    <url>https://github.intuit.com/billingcomm-custpayments/pymt-lib</url>
                  </scm>
                </project>
                """;
        byte[] jar = buildJar(Map.of(
                "META-INF/maven/com.intuit.payment.common/pymt-lib/pom.xml", pom));
        Optional<JarScmExtractor.GitHubCoords> c = JarScmExtractor.extract(jar);
        assertTrue(c.isPresent());
        assertEquals("billingcomm-custpayments", c.get().owner());
        assertEquals("pymt-lib", c.get().name());
    }

    @Test void emptyWhenNoEmbeddedPom() throws Exception {
        byte[] jar = buildJar(Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));
        assertTrue(JarScmExtractor.extract(jar).isEmpty());
    }

    @Test void prefersUrlOverConnectionIfUrlIsGithub() throws Exception {
        String pom = """
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>c</groupId><artifactId>lib</artifactId><version>1.0</version>
                  <scm>
                    <url>https://github.com/a/b</url>
                    <connection>scm:git:https://gitlab.com/x/y.git</connection>
                  </scm>
                </project>
                """;
        byte[] jar = buildJar(Map.of("META-INF/maven/c/lib/pom.xml", pom));
        Optional<JarScmExtractor.GitHubCoords> c = JarScmExtractor.extract(jar);
        assertTrue(c.isPresent());
        assertEquals("a", c.get().owner());
        assertEquals("b", c.get().name());
    }
}
