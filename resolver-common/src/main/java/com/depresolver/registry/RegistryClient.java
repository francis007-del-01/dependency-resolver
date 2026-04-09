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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RegistryClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);
    private static final String OWNER = "myorg";
    private static final String REPO = "dependency-resolver-cli";
    private static final String BRANCH = "main";
    private static final String REGISTRY_DIR = "registry";
    private static final String VERSIONS_FILE = REGISTRY_DIR + "/versions.yaml";
    private static final int MAX_RETRIES = 3;

    private final GitHubClient gitHubClient;
    private final ObjectMapper yamlMapper;

    public RegistryClient(String githubToken) {
        this(new GitHubClient(githubToken));
    }

    public RegistryClient(GitHubClient gitHubClient) {
        this.gitHubClient = gitHubClient;
        this.yamlMapper = new ObjectMapper(new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    }

    public VersionRegistry readRegistry() throws IOException, InterruptedException {
        GitHubClient.FileContent file = gitHubClient.getFileContentOrNull(OWNER, REPO, VERSIONS_FILE, BRANCH);
        if (file == null) {
            return new VersionRegistry();
        }
        return yamlMapper.readValue(file.content(), VersionRegistry.class);
    }

    public void upsertArtifact(ArtifactEntry entry) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                GitHubClient.FileContent file = gitHubClient.getFileContentOrNull(OWNER, REPO, VERSIONS_FILE, BRANCH);

                VersionRegistry registry;
                String fileSha;
                if (file != null) {
                    registry = yamlMapper.readValue(file.content(), VersionRegistry.class);
                    fileSha = file.sha();
                } else {
                    registry = new VersionRegistry();
                    fileSha = null;
                }

                Optional<ArtifactEntry> existing = registry.getArtifacts().stream()
                        .filter(a -> a.getGroupId().equals(entry.getGroupId())
                                && a.getArtifactId().equals(entry.getArtifactId()))
                        .findFirst();

                if (existing.isPresent()) {
                    ArtifactEntry e = existing.get();
                    e.setLatestVersion(entry.getLatestVersion());
                    e.setRepoOwner(entry.getRepoOwner());
                    e.setRepoName(entry.getRepoName());
                    e.setPomPath(entry.getPomPath());
                    e.setUpdatedAt(Instant.now().toString());
                } else {
                    entry.setUpdatedAt(Instant.now().toString());
                    registry.getArtifacts().add(entry);
                }

                String yaml = yamlMapper.writeValueAsString(registry);
                gitHubClient.createOrUpdateFile(OWNER, REPO, VERSIONS_FILE, yaml,
                        fileSha, BRANCH, "chore: update version registry");
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

        GitHubClient.FileContent existing = gitHubClient.getFileContentOrNull(OWNER, REPO, path, BRANCH);
        String sha = existing != null ? existing.sha() : null;

        gitHubClient.createOrUpdateFile(OWNER, REPO, path, pomContent,
                sha, BRANCH, "chore: update pom for %s:%s (%s)".formatted(groupId, artifactId, branch));
        log.info("Pushed pom for {}:{} ({})", groupId, artifactId, branch);
    }

    /**
     * Lists all groupId/artifactId pairs that have poms in the registry.
     * Returns list of "groupId:artifactId" strings.
     */
    public List<String> listAllPomArtifacts() throws IOException, InterruptedException {
        String pomsPath = REGISTRY_DIR + "/poms";
        List<String> groupDirs = gitHubClient.listDirectoriesOrEmpty(OWNER, REPO, pomsPath, BRANCH);

        List<String> result = new ArrayList<>();
        for (String groupId : groupDirs) {
            List<String> artifactDirs = gitHubClient.listDirectoriesOrEmpty(OWNER, REPO, pomsPath + "/" + groupId, BRANCH);
            for (String artifactId : artifactDirs) {
                result.add(groupId + ":" + artifactId);
            }
        }
        return result;
    }

    public List<String> listBranches(String groupId, String artifactId) {
        String path = "%s/poms/%s/%s".formatted(REGISTRY_DIR, groupId, artifactId);
        return gitHubClient.listDirectoriesOrEmpty(OWNER, REPO, path, BRANCH);
    }

    public String readPom(String groupId, String artifactId, String branch) throws IOException, InterruptedException {
        String path = "%s/poms/%s/%s/%s/pom.xml".formatted(REGISTRY_DIR, groupId, artifactId, branch);
        GitHubClient.FileContent file = gitHubClient.getFileContent(OWNER, REPO, path, BRANCH);
        return file.content();
    }
}
