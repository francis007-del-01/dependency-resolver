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

    @Parameter(property = "github.token", required = true)
    private String githubToken;

    @Parameter(property = "repo.owner", required = true)
    private String repoOwner;

    @Parameter(property = "repo.name", required = true)
    private String repoName;

    @Parameter(property = "repo.currentBranch", required = true)
    private String currentBranch;

    @Parameter(property = "repo.targetBranches")
    private String targetBranches;

    @Parameter(property = "repo.pomPath", defaultValue = "pom.xml")
    private String pomPath;

    @Parameter(property = "register.triggerBranch")
    private String triggerBranch;

    @Override
    public void execute() throws MojoExecutionException {
        String groupId = project.getGroupId();
        String artifactId = project.getArtifactId();
        String version = project.getVersion();

        List<String> branches = targetBranches != null
                ? Arrays.stream(targetBranches.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : List.of();

        boolean isTargetBranch = !branches.isEmpty() && branches.contains(currentBranch);
        boolean isTriggerBranch = triggerBranch != null && triggerBranch.equals(currentBranch);

        if (!isTargetBranch && !isTriggerBranch) {
            getLog().info("Skipping — branch %s is not in targetBranches %s and not triggerBranch %s"
                    .formatted(currentBranch, branches, triggerBranch));
            return;
        }

        try {
            RegistryClient registryClient = new RegistryClient(new GitHubClient(githubToken));

            Path pomFile = project.getFile().toPath();
            String pomContent = Files.readString(pomFile);

            if (isTargetBranch) {
                getLog().info("Pushing pom for %s:%s (%s)".formatted(groupId, artifactId, currentBranch));
                registryClient.pushPom(groupId, artifactId, currentBranch, pomContent);
            }

            if (isTriggerBranch) {
                getLog().info("Registering %s:%s:%s from trigger branch %s".formatted(groupId, artifactId, version, triggerBranch));
                ArtifactEntry entry = ArtifactEntry.builder()
                        .groupId(groupId)
                        .artifactId(artifactId)
                        .latestVersion(version)
                        .repoOwner(repoOwner)
                        .repoName(repoName)
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
}
