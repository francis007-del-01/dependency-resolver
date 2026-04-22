package com.depresolver.artifactory;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GitPropertiesExtractorTest {

    private static byte[] buildJar(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    @Test void emptyWhenNoGitProperties() throws Exception {
        byte[] jar = buildJar(Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));
        assertTrue(GitPropertiesExtractor.extract(jar).isEmpty());
    }

    @Test void parsesRootLevelGitProperties() throws Exception {
        byte[] jar = buildJar(Map.of(
                "git.properties", "git.commit.id=a91ea83f6c4d8b2e9c8a7f1d3e5b2c6a8d9e0f1b2\ngit.dirty=false\n"));
        Optional<GitPropertiesExtractor.GitInfo> info = GitPropertiesExtractor.extract(jar);
        assertTrue(info.isPresent());
        assertEquals("a91ea83f6c4d8b2e9c8a7f1d3e5b2c6a8d9e0f1b2", info.get().commitSha());
        assertFalse(info.get().dirty());
    }

    @Test void parsesMetaInfGitProperties() throws Exception {
        byte[] jar = buildJar(Map.of(
                "META-INF/git.properties", "git.commit.id=deadbeefcafebabefeedface00000000aabbccdd\n"));
        Optional<GitPropertiesExtractor.GitInfo> info = GitPropertiesExtractor.extract(jar);
        assertTrue(info.isPresent());
        assertEquals("deadbeefcafebabefeedface00000000aabbccdd", info.get().commitSha());
    }

    @Test void reportsDirtyWhenFlagSet() throws Exception {
        byte[] jar = buildJar(Map.of(
                "git.properties", "git.commit.id=abc123def456abc123def456abc123def456abcd\ngit.dirty=true\n"));
        assertTrue(GitPropertiesExtractor.extract(jar).get().dirty());
    }

    @Test void emptyWhenCommitIdMissing() throws Exception {
        byte[] jar = buildJar(Map.of("git.properties", "git.dirty=false\n"));
        assertTrue(GitPropertiesExtractor.extract(jar).isEmpty());
    }
}
