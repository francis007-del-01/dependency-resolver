package com.depresolver.registry;

import com.depresolver.github.GitHubClient;
import com.depresolver.github.GitHubConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

public class RegistryClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);
    private static final String OWNER = "myorg";
    private static final String REPO = "dependency-resolver-cli";
    private static final String REGISTRY_DIR = "registry";
    private static final String VERSIONS_FILE = REGISTRY_DIR + "/versions.yaml";
    private static final int MAX_RETRIES = 3;

    private final GitHubClient gitHubClient;
    private final String registryBranch;
    private final ObjectMapper yamlMapper;

    public RegistryClient(GitHubClient gitHubClient, String registryBranch) {
        this.gitHubClient = gitHubClient;
        this.registryBranch = registryBranch;
        this.yamlMapper = new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    }

    public record RegistrySnapshot(VersionRegistry registry, String fileSha) {}

    public RegistrySnapshot readRegistryWithSha() throws IOException, InterruptedException {
        GitHubClient.FileContent file = gitHubClient.getFileContentOrNull(
                OWNER, REPO, VERSIONS_FILE, registryBranch);

        if (file == null) {
            return new RegistrySnapshot(new VersionRegistry(), null);
        }

        VersionRegistry registry = yamlMapper.readValue(file.content(), VersionRegistry.class);
        return new RegistrySnapshot(registry, file.sha());
    }

    public void writeRegistry(VersionRegistry registry, String fileSha) throws IOException, InterruptedException {
        String yaml = yamlMapper.writeValueAsString(registry);
        gitHubClient.createOrUpdateFile(
                OWNER, REPO, VERSIONS_FILE, yaml,
                fileSha, registryBranch, "chore: update version registry");
    }

    public void upsertArtifact(ArtifactEntry entry) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                RegistrySnapshot snapshot = readRegistryWithSha();
                VersionRegistry registry = snapshot.registry();

                Optional<ArtifactEntry> existing = registry.getArtifacts().stream()
                        .filter(a -> a.getGroupId().equals(entry.getGroupId())
                                && a.getArtifactId().equals(entry.getArtifactId()))
                        .findFirst();

                if (existing.isPresent()) {
                    ArtifactEntry e = existing.get();
                    e.setLatestVersion(entry.getLatestVersion());
                    e.setRepoOwner(entry.getRepoOwner());
                    e.setRepoName(entry.getRepoName());
                    e.setTargetBranches(entry.getTargetBranches());
                    e.setPomPath(entry.getPomPath());
                    e.setUpdatedAt(Instant.now().toString());
                } else {
                    entry.setUpdatedAt(Instant.now().toString());
                    registry.getArtifacts().add(entry);
                }

                writeRegistry(registry, snapshot.fileSha());
                log.info("Registered {}:{} version {}", entry.getGroupId(), entry.getArtifactId(), entry.getLatestVersion());
                return;
            } catch (GitHubConflictException e) {
                if (attempt == MAX_RETRIES) {
                    throw new IOException("Failed to update registry after %d retries".formatted(MAX_RETRIES), e);
                }
                log.warn("Registry conflict on attempt {}, retrying...", attempt);
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
    }

    public void pushPom(String groupId, String artifactId, String branch, String pomContent) throws IOException, InterruptedException {
        String path = "%s/poms/%s/%s/%s/pom.xml".formatted(REGISTRY_DIR, groupId, artifactId, branch);

        GitHubClient.FileContent existing = gitHubClient.getFileContentOrNull(
                OWNER, REPO, path, registryBranch);

        String sha = existing != null ? existing.sha() : null;
        gitHubClient.createOrUpdateFile(
                OWNER, REPO, path, pomContent,
                sha, registryBranch,
                "chore: update pom for %s:%s (%s)".formatted(groupId, artifactId, branch));
        log.info("Pushed pom for {}:{} ({})", groupId, artifactId, branch);
    }

    public String readPom(String groupId, String artifactId, String branch) throws IOException, InterruptedException {
        String path = "%s/poms/%s/%s/%s/pom.xml".formatted(REGISTRY_DIR, groupId, artifactId, branch);
        GitHubClient.FileContent file = gitHubClient.getFileContent(
                OWNER, REPO, path, registryBranch);
        return file.content();
    }
}
