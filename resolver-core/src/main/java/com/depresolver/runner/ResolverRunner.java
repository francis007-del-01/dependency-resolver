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
    private static final String ARG_OWNER = "owner";
    private static final String ARG_REPO = "repo";
    private static final String ARG_BRANCH = "branch";
    private static final String ARG_POM_PATH = "pomPath";
    private static final String ARG_RELEASE_GROUP_ID = "releaseGroupId";
    private static final String ARG_RELEASE_ARTIFACT = "releaseArtifact";
    private static final String DEFAULT_POM_PATH = "pom.xml";

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
        String owner = requireArg(args, ARG_OWNER);
        String repo = requireArg(args, ARG_REPO);
        String branch = requireArg(args, ARG_BRANCH);
        String pomPath = firstArg(args, ARG_POM_PATH, DEFAULT_POM_PATH);
        List<String> releaseGroupIds = optionalMultiArg(args, ARG_RELEASE_GROUP_ID);
        List<String> releaseArtifacts = optionalMultiArg(args, ARG_RELEASE_ARTIFACT);
        if (releaseGroupIds.isEmpty() && releaseArtifacts.isEmpty()) {
            throw new IllegalArgumentException("At least one of --" + ARG_RELEASE_GROUP_ID
                    + " or --" + ARG_RELEASE_ARTIFACT + " is required");
        }
        Set<String> releaseArtifactKeys = filterArtifactKeysOverlappingGroups(
                parseReleaseArtifactKeys(releaseArtifacts), releaseGroupIds);

        log.info("Resolving dependencies for {}/{} on branch {} (pom: {}, releaseGroupIds={}, releaseArtifacts={})",
                owner, repo, branch, pomPath, releaseGroupIds, releaseArtifactKeys);

        GitHubClient.FileContent pomFile = gitHubClient.getFileContent(owner, repo, pomPath, branch);
        String pomContent = pomFile.content();
        List<PomCoordinates> trackedCoordinates = pomManager.listCoordinatesForTargets(
                pomContent, releaseGroupIds, releaseArtifactKeys);

        Set<String> matchedGroups = new LinkedHashSet<>();
        for (PomCoordinates coordinate : trackedCoordinates) {
            matchedGroups.add(coordinate.groupId());
        }
        for (String groupId : releaseGroupIds) {
            if (!matchedGroups.contains(groupId)) {
                log.warn("No dependencies found in pom for release group {}", groupId);
            }
        }
        Set<String> matchedArtifacts = new LinkedHashSet<>();
        for (PomCoordinates coordinate : trackedCoordinates) {
            matchedArtifacts.add(coordinate.key());
        }
        for (String artifactKey : releaseArtifactKeys) {
            if (!matchedArtifacts.contains(artifactKey)) {
                log.warn("No dependency found in pom for release artifact {}", artifactKey);
            }
        }

        if (trackedCoordinates.isEmpty()) {
            log.info("No matching dependencies found for release selectors (groups={}, artifacts={}); nothing to do",
                    releaseGroupIds, releaseArtifactKeys);
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
                owner, repo, buildPrTitle(bumps), buildPrBody(bumps, releaseGroupIds, releaseArtifactKeys), runBranch, branch);
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

    private static String buildPrBody(List<BumpedDependency> bumps, List<String> releaseGroupIds,
                                      Set<String> releaseArtifactKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("Automated release dependency update.\n\n");
        if (!releaseGroupIds.isEmpty()) {
            sb.append("Release groups:\n");
            for (String groupId : releaseGroupIds) {
                sb.append("- ").append(groupId).append('\n');
            }
            sb.append('\n');
        }
        if (!releaseArtifactKeys.isEmpty()) {
            sb.append("Release artifacts:\n");
            for (String artifactKey : releaseArtifactKeys) {
                sb.append("- ").append(artifactKey).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Updated dependencies:\n");
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

    private static List<String> optionalMultiArg(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) return List.of();
        Set<String> deduped = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) continue;
            for (String token : raw.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) deduped.add(trimmed);
            }
        }
        return new ArrayList<>(deduped);
    }

    private static Set<String> parseReleaseArtifactKeys(List<String> releaseArtifacts) {
        Set<String> out = new LinkedHashSet<>();
        for (String selector : releaseArtifacts) {
            String normalized = selector;
            if (normalized.contains("::")) {
                normalized = normalized.replace("::", ":");
            }
            String[] parts = normalized.split(":", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid --" + ARG_RELEASE_ARTIFACT + " value '%s'. Expected groupId:artifactId".formatted(selector));
            }
            out.add(parts[0].trim() + ":" + parts[1].trim());
        }
        return out;
    }

    private static Set<String> filterArtifactKeysOverlappingGroups(Set<String> artifactKeys, List<String> releaseGroupIds) {
        if (artifactKeys.isEmpty() || releaseGroupIds.isEmpty()) {
            return artifactKeys;
        }
        Set<String> groups = new LinkedHashSet<>(releaseGroupIds);
        Set<String> filtered = new LinkedHashSet<>();
        for (String artifactKey : artifactKeys) {
            int sep = artifactKey.indexOf(':');
            if (sep <= 0) {
                filtered.add(artifactKey);
                continue;
            }
            String groupId = artifactKey.substring(0, sep);
            if (!groups.contains(groupId)) {
                filtered.add(artifactKey);
            }
        }
        return filtered;
    }
}
