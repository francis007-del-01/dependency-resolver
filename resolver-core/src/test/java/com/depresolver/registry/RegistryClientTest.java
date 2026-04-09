package com.depresolver.registry;

import com.depresolver.github.GitHubClient;
import com.depresolver.github.GitHubConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistryClientTest {

    @Mock
    private GitHubClient gitHubClient;

    private RegistryClient registryClient;
    private ObjectMapper yamlMapper;

    @BeforeEach
    void setUp() {
        registryClient = new RegistryClient(gitHubClient, "main");
        yamlMapper = new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    }

    @Test
    void readRegistryWithSha() throws Exception {
        String yaml = Files.readString(Path.of("src/test/resources/sample-registry.yaml"));
        when(gitHubClient.getFileContentOrNull("myorg", "dependency-resolver-cli", "registry/versions.yaml", "main"))
                .thenReturn(new GitHubClient.FileContent(yaml, "abc123", "registry/versions.yaml"));

        RegistryClient.RegistrySnapshot snapshot = registryClient.readRegistryWithSha();

        assertNotNull(snapshot.registry());
        assertEquals("abc123", snapshot.fileSha());
        assertEquals(3, snapshot.registry().getArtifacts().size());

        ArtifactEntry pool = snapshot.registry().getArtifacts().get(0);
        assertEquals("com.pool", pool.getGroupId());
        assertEquals("pool", pool.getArtifactId());
        assertEquals("2.0.0", pool.getLatestVersion());
        assertEquals("myorg", pool.getRepoOwner());
    }

    @Test
    void readRegistryEmpty() throws Exception {
        when(gitHubClient.getFileContentOrNull("myorg", "dependency-resolver-cli", "registry/versions.yaml", "main"))
                .thenReturn(null);

        RegistryClient.RegistrySnapshot snapshot = registryClient.readRegistryWithSha();

        assertNotNull(snapshot.registry());
        assertNull(snapshot.fileSha());
        assertTrue(snapshot.registry().getArtifacts().isEmpty());
    }

    @Test
    void upsertArtifactNewEntry() throws Exception {
        // Empty registry
        when(gitHubClient.getFileContentOrNull("myorg", "dependency-resolver-cli", "registry/versions.yaml", "main"))
                .thenReturn(null);

        ArtifactEntry entry = ArtifactEntry.builder()
                .groupId("com.new")
                .artifactId("new-lib")
                .latestVersion("1.0.0")
                .repoOwner("myorg")
                .repoName("new-lib")
                .targetBranches(java.util.List.of("master"))
                .pomPath("pom.xml")
                .build();

        registryClient.upsertArtifact(entry);

        verify(gitHubClient).createOrUpdateFile(
                eq("myorg"), eq("dependency-resolver-cli"), eq("registry/versions.yaml"),
                argThat(content -> content.contains("new-lib") && content.contains("1.0.0")),
                isNull(), eq("main"), anyString());
    }

    @Test
    void upsertArtifactUpdateExisting() throws Exception {
        String yaml = Files.readString(Path.of("src/test/resources/sample-registry.yaml"));
        when(gitHubClient.getFileContentOrNull("myorg", "dependency-resolver-cli", "registry/versions.yaml", "main"))
                .thenReturn(new GitHubClient.FileContent(yaml, "abc123", "registry/versions.yaml"));

        ArtifactEntry entry = ArtifactEntry.builder()
                .groupId("com.pool")
                .artifactId("pool")
                .latestVersion("3.0.0")
                .repoOwner("myorg")
                .repoName("pool")
                .targetBranches(java.util.List.of("master"))
                .pomPath("pom.xml")
                .build();

        registryClient.upsertArtifact(entry);

        verify(gitHubClient).createOrUpdateFile(
                eq("myorg"), eq("dependency-resolver-cli"), eq("registry/versions.yaml"),
                argThat(content -> content.contains("\"3.0.0\"") && content.contains("pool")),
                eq("abc123"), eq("main"), anyString());
    }

    @Test
    void upsertArtifactRetriesOnConflict() throws Exception {
        String yaml = Files.readString(Path.of("src/test/resources/sample-registry.yaml"));
        when(gitHubClient.getFileContentOrNull("myorg", "dependency-resolver-cli", "registry/versions.yaml", "main"))
                .thenReturn(new GitHubClient.FileContent(yaml, "abc123", "registry/versions.yaml"));

        // First write fails with conflict, second succeeds
        doThrow(new GitHubConflictException("conflict"))
                .doNothing()
                .when(gitHubClient).createOrUpdateFile(
                        eq("myorg"), eq("dependency-resolver-cli"), eq("registry/versions.yaml"),
                        anyString(), anyString(), eq("main"), anyString());

        ArtifactEntry entry = ArtifactEntry.builder()
                .groupId("com.pool").artifactId("pool").latestVersion("3.0.0")
                .repoOwner("myorg").repoName("pool").targetBranches(java.util.List.of("master")).pomPath("pom.xml")
                .build();

        registryClient.upsertArtifact(entry);

        verify(gitHubClient, times(2)).createOrUpdateFile(
                eq("myorg"), eq("dependency-resolver-cli"), eq("registry/versions.yaml"),
                anyString(), anyString(), eq("main"), anyString());
    }

    @Test
    void pushPomNewFile() throws Exception {
        when(gitHubClient.getFileContentOrNull("myorg", "dependency-resolver-cli", "registry/poms/com.pool/pool/master/pom.xml", "main"))
                .thenReturn(null);

        registryClient.pushPom("com.pool", "pool", "master", "<project>...</project>");

        verify(gitHubClient).createOrUpdateFile(
                eq("myorg"), eq("dependency-resolver-cli"), eq("registry/poms/com.pool/pool/master/pom.xml"),
                eq("<project>...</project>"), isNull(), eq("main"), anyString());
    }

    @Test
    void pushPomExistingFile() throws Exception {
        when(gitHubClient.getFileContentOrNull("myorg", "dependency-resolver-cli", "registry/poms/com.pool/pool/master/pom.xml", "main"))
                .thenReturn(new GitHubClient.FileContent("<old/>", "sha456", "registry/poms/com.pool/pool/master/pom.xml"));

        registryClient.pushPom("com.pool", "pool", "master", "<project>new</project>");

        verify(gitHubClient).createOrUpdateFile(
                eq("myorg"), eq("dependency-resolver-cli"), eq("registry/poms/com.pool/pool/master/pom.xml"),
                eq("<project>new</project>"), eq("sha456"), eq("main"), anyString());
    }

    @Test
    void readPom() throws Exception {
        when(gitHubClient.getFileContent("myorg", "dependency-resolver-cli", "registry/poms/com.pool/pool/master/pom.xml", "main"))
                .thenReturn(new GitHubClient.FileContent("<project/>", "sha789", "registry/poms/com.pool/pool/master/pom.xml"));

        String content = registryClient.readPom("com.pool", "pool", "master");
        assertEquals("<project/>", content);
    }
}
