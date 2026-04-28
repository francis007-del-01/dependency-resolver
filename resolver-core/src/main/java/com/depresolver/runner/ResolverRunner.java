package com.depresolver.runner;

import com.depresolver.artifactory.ArtifactoryClient;
import com.depresolver.github.GitHubClient;
import com.depresolver.pom.BumpedDependency;
import com.depresolver.pom.PomManager;
import com.depresolver.pom.PomManager.PomCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ResolverRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ResolverRunner.class);

    private final GitHubClient gitHubClient;
    private final ArtifactoryClient artifactoryClient;
    private final PomManager pomManager;

    public ResolverRunner(GitHubClient gitHubClient, ArtifactoryClient artifactoryClient, PomManager pomManager) {
        this.gitHubClient = gitHubClient;
        this.artifactoryClient = artifactoryClient;
        this.pomManager = pomManager;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String owner = requireArg(args, "owner");
        String repo = requireArg(args, "repo");
        String branch = requireArg(args, "branch");
        String pomPath = firstArg(args, "pomPath", "pom.xml");
        List<String> releaseGroupIds = requiredMultiArg(args, "releaseGroupId");

        log.info("Resolving dependencies for {}/{} on branch {} (pom: {}, releaseGroupIds={})",
                owner, repo, branch, pomPath, releaseGroupIds);

        GitHubClient.FileContent pomFile = gitHubClient.getFileContent(owner, repo, pomPath, branch);
        String pomContent = pomFile.content();
        List<PomCoordinates> trackedCoordinates = pomManager.listCoordinatesForGroupIds(pomContent, releaseGroupIds);

        Set<String> matchedGroups = new LinkedHashSet<>();
        for (PomCoordinates coordinate : trackedCoordinates) {
            matchedGroups.add(coordinate.groupId());
        }
        for (String groupId : releaseGroupIds) {
            if (!matchedGroups.contains(groupId)) {
                log.warn("No dependencies found in pom for release group {}", groupId);
            }
        }

        if (trackedCoordinates.isEmpty()) {
            log.info("No matching dependencies found for release group IDs {}; nothing to do", releaseGroupIds);
            return;
        }

        Map<String, String> latestVersions = new HashMap<>();
        Set<String> resolvedCoordinates = new LinkedHashSet<>();
        for (PomCoordinates coordinate : trackedCoordinates) {
            String key = coordinate.groupId() + ":" + coordinate.artifactId();
            if (!resolvedCoordinates.add(key)) {
                continue;
            }
            var latest = artifactoryClient.latestReleaseVersion(coordinate.groupId(), coordinate.artifactId());
            if (latest.isPresent()) {
                latestVersions.put(key, latest.get());
                log.info("  release {} -> {}", key, latest.get());
            } else {
                log.warn("  release {} -> no release found in Artifactory", key);
            }
        }

        List<BumpedDependency> bumps = pomManager.findBumpsFromLatestVersions(pomContent, latestVersions);

        if (bumps.isEmpty()) {
            log.info("{}/{} ({}) is already up to date", owner, repo, branch);
            return;
        }

        log.info("{}/{} ({}) needs {} update(s):", owner, repo, branch, bumps.size());
        for (BumpedDependency b : bumps) {
            log.info("  - {}:{} {} -> {}", b.groupId(), b.artifactId(), b.oldVersion(), b.newVersion());
        }

        String updated = pomManager.applyBumps(pomContent, bumps);
        if (updated.equals(pomContent)) {
            log.info("No textual change after bump; skipping commit");
            return;
        }

        String commitMessage = buildCommitMessage(bumps);
        String runBranch = buildRunBranchName(branch);
        String baseSha = gitHubClient.getBranchHeadSha(owner, repo, branch);
        gitHubClient.createBranch(owner, repo, runBranch, baseSha);
        gitHubClient.updateFile(owner, repo, pomPath, updated, pomFile.sha(), runBranch, commitMessage);
        GitHubClient.PullRequest pr = gitHubClient.createPullRequest(
                owner, repo, buildPrTitle(bumps), buildPrBody(bumps, releaseGroupIds), runBranch, branch);
        log.info("Created PR #{} for {}/{}: {}", pr.number(), owner, repo, pr.url());
    }

    private static String buildCommitMessage(List<BumpedDependency> bumps) {
        if (bumps.size() == 1) {
            BumpedDependency b = bumps.get(0);
            return "chore(deps): update %s:%s to %s".formatted(b.groupId(), b.artifactId(), b.newVersion());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("chore(deps): update ").append(bumps.size()).append(" dependencies\n\n");
        for (BumpedDependency b : bumps) {
            sb.append("- ").append(b.groupId()).append(':').append(b.artifactId())
              .append(' ').append(b.oldVersion()).append(" -> ").append(b.newVersion()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String buildPrTitle(List<BumpedDependency> bumps) {
        if (bumps.size() == 1) {
            BumpedDependency b = bumps.get(0);
            return "chore(deps): bump %s:%s to %s".formatted(b.groupId(), b.artifactId(), b.newVersion());
        }
        return "chore(deps): bump %s dependencies".formatted(bumps.size());
    }

    private static String buildPrBody(List<BumpedDependency> bumps, List<String> releaseGroupIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("Automated release dependency update.\n\n");
        sb.append("Release groups:\n");
        for (String groupId : releaseGroupIds) {
            sb.append("- ").append(groupId).append('\n');
        }
        sb.append("\nUpdated dependencies:\n");
        for (BumpedDependency b : bumps) {
            sb.append("- ").append(b.groupId()).append(':').append(b.artifactId())
                    .append(' ').append(b.oldVersion()).append(" -> ").append(b.newVersion()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String buildRunBranchName(String baseBranch) {
        return "resolver/release-" + sanitizeBranchComponent(baseBranch) + "-" + Instant.now().toEpochMilli();
    }

    private static String sanitizeBranchComponent(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private static String requireArg(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            throw new IllegalArgumentException("Missing required --" + name + " argument");
        }
        return values.get(0);
    }

    private static String firstArg(ApplicationArguments args, String name, String defaultValue) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            return defaultValue;
        }
        return values.get(0);
    }

    private static List<String> requiredMultiArg(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Missing required --" + name + " argument");
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) continue;
            for (String token : raw.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) deduped.add(trimmed);
            }
        }
        if (deduped.isEmpty()) {
            throw new IllegalArgumentException("Missing required --" + name + " argument");
        }
        return new ArrayList<>(deduped);
    }
}
