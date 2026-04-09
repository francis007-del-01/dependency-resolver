package com.depresolver.plugin;

import com.depresolver.github.GitHubClient;
import com.depresolver.registry.ArtifactEntry;
import com.depresolver.registry.RegistryClient;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Mojo(name = "register", defaultPhase = LifecyclePhase.DEPLOY)
public class RegisterVersionMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "registry.branch", defaultValue = "main")
    private String registryBranch;

    @Parameter(property = "github.token", required = true)
    private String githubToken;

    @Parameter(property = "repo.owner", required = true)
    private String repoOwner;

    @Parameter(property = "repo.name", required = true)
    private String repoName;

    @Parameter(property = "repo.targetBranches", defaultValue = "master")
    private String targetBranches;

    @Parameter(property = "repo.pomPath", defaultValue = "pom.xml")
    private String pomPath;

    @Parameter(property = "register.triggerBranch", defaultValue = "master")
    private String triggerBranch;


    @Override
    public void execute() throws MojoExecutionException {
        String groupId = project.getGroupId();
        String artifactId = project.getArtifactId();
        String version = project.getVersion();

        String currentBranch = detectBranch();
        List<String> branches = Arrays.stream(targetBranches.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        boolean isTargetBranch = currentBranch == null || branches.contains(currentBranch);
        boolean isTriggerBranch = currentBranch == null || currentBranch.equals(triggerBranch);

        if (!isTargetBranch && !isTriggerBranch) {
            getLog().info("Skipping — branch %s is not in targetBranches %s and not triggerBranch %s"
                    .formatted(currentBranch, branches, triggerBranch));
            return;
        }

        try {
            GitHubClient gitHubClient = new GitHubClient(githubToken);
            RegistryClient registryClient = new RegistryClient(gitHubClient, registryBranch);
            Path pomFile = project.getFile().toPath();
            String pomContent = Files.readString(pomFile);

            // Always push pom for the current branch if it's a target branch
            if (isTargetBranch) {
                getLog().info("Pushing pom for %s:%s (%s)".formatted(groupId, artifactId, currentBranch));
                registryClient.pushPom(groupId, artifactId, currentBranch, pomContent);
            }

            // Only update versions.yaml from the trigger branch
            if (isTriggerBranch) {
                getLog().info("Registering %s:%s:%s from trigger branch %s".formatted(groupId, artifactId, version, triggerBranch));

                ArtifactEntry entry = ArtifactEntry.builder()
                        .groupId(groupId)
                        .artifactId(artifactId)
                        .latestVersion(version)
                        .repoOwner(repoOwner)
                        .repoName(repoName)
                        .targetBranches(branches)
                        .pomPath(pomPath)
                        .build();
                registryClient.upsertArtifact(entry);
            }

            getLog().info("Done for %s:%s (%s)".formatted(groupId, artifactId, currentBranch));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new MojoExecutionException("Failed to register artifact", e);
        }
    }

    private String detectBranch() {
        // Try CI environment variables first (Jenkins, GitHub Actions, etc.)
        String[] envVars = {"GIT_BRANCH", "BRANCH_NAME", "GITHUB_REF_NAME", "CI_COMMIT_BRANCH"};
        for (String envVar : envVars) {
            String branch = System.getenv(envVar);
            if (branch != null && !branch.isBlank()) {
                // Jenkins prefixes with origin/
                if (branch.startsWith("origin/")) {
                    branch = branch.substring("origin/".length());
                }
                return branch;
            }
        }

        // Fallback: try git command
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(project.getBasedir());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            if (exitCode == 0 && !output.isBlank()) {
                return output;
            }
        } catch (Exception e) {
            getLog().debug("Could not detect git branch: " + e.getMessage());
        }

        // If we can't detect the branch, allow registration (conservative)
        getLog().warn("Could not detect current git branch — allowing registration");
        return null;
    }
}
