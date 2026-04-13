package com.depresolver.scheduler;

import com.depresolver.config.BranchConfig;
import com.depresolver.config.RepoConfig;
import com.depresolver.config.ResolverConfig;
import com.depresolver.github.GitHubClient;
import com.depresolver.github.PullRequestCreator;
import com.depresolver.github.PullRequestCreator.BumpedDependency;
import com.depresolver.pom.PomModifier;
import com.depresolver.pom.PomParser;
import com.depresolver.pom.PomParser.DependencyInfo;
import com.depresolver.pom.PomParser.PomInfo;
import com.depresolver.scanner.DependencyMatch;
import com.depresolver.scanner.DependencyMatch.VersionType;
import com.depresolver.version.VersionComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ResolverScheduler implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ResolverScheduler.class);

    private final ResolverConfig resolverConfig;
    private final GitHubClient gitHubClient;
    private final PomParser pomParser;
    private final PomModifier pomModifier;
    private final PullRequestCreator prCreator;

    public ResolverScheduler(ResolverConfig resolverConfig, GitHubClient gitHubClient,
                             PomParser pomParser, PomModifier pomModifier, PullRequestCreator prCreator) {
        this.resolverConfig = resolverConfig;
        this.gitHubClient = gitHubClient;
        this.pomParser = pomParser;
        this.pomModifier = pomModifier;
        this.prCreator = prCreator;
    }

    @Override
    public void run(String... args) {
        try {
            log.info("Dependency Resolver starting...");

            // Phase 1: Build latest versions map from trigger branches
            Map<String, String> latestVersions = new HashMap<>();
            Map<String, String> updatedByMap = new HashMap<>();

            for (RepoConfig repo : resolverConfig.getRepos()) {
                if (repo.getTriggerBranch() == null) continue;

                try {
                    String pomContent = fetchPom(repo.getOwner(), repo.getName(), repo.getPomPath(), repo.getTriggerBranch());
                    PomInfo pomInfo = pomParser.parse(pomContent);

                    if (pomInfo.groupId() != null && pomInfo.artifactId() != null && pomInfo.version() != null) {
                        String key = pomInfo.groupId() + ":" + pomInfo.artifactId();
                        latestVersions.put(key, pomInfo.version());

                        String committer = gitHubClient.getLastCommitter(repo.getOwner(), repo.getName(), repo.getTriggerBranch());
                        if (committer != null) {
                            updatedByMap.put(key, committer);
                        }

                        log.info("  {} = {} (from {}/{}:{}, by {})", key, pomInfo.version(),
                                repo.getOwner(), repo.getName(), repo.getTriggerBranch(),
                                committer != null ? committer : "unknown");
                    }
                } catch (Exception e) {
                    log.warn("Could not read trigger version from {}/{}: {}", repo.getOwner(), repo.getName(), e.getMessage());
                }
            }
            log.info("Loaded {} artifact versions from trigger branches", latestVersions.size());

            // Phase 2: Process target branches
            int created = 0, skipped = 0, failed = 0;

            for (RepoConfig repo : resolverConfig.getRepos()) {
                if (repo.getTargetBranches() == null || repo.getTargetBranches().isEmpty()) continue;

                for (BranchConfig branch : repo.getTargetBranches()) {
                    try {
                        int result = processBranch(repo, branch, latestVersions, updatedByMap);
                        if (result > 0) created++;
                        else skipped++;
                    } catch (Exception e) {
                        log.error("Failed {}/{} ({}): {}", repo.getOwner(), repo.getName(), branch.getName(), e.getMessage());
                        failed++;
                    }
                }
            }

            log.info("Done. Created: {}, Skipped: {}, Failed: {}", created, skipped, failed);
        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
        }
    }

    private int processBranch(RepoConfig repo, BranchConfig branch, Map<String, String> latestVersions,
                              Map<String, String> updatedByMap) throws Exception {
        log.info("Processing {}/{} on branch {}", repo.getOwner(), repo.getName(), branch.getName());

        String pomContent = fetchPom(repo.getOwner(), repo.getName(), repo.getPomPath(), branch.getName());
        PomInfo pomInfo = pomParser.parse(pomContent);

        List<BumpedDependency> bumps = new ArrayList<>();
        String updatedPom = pomContent;

        for (DependencyInfo dep : pomInfo.dependencies()) {
            updatedPom = checkAndBump(dep, dep.versionType(), latestVersions, updatedByMap, repo, updatedPom, bumps);
        }
        for (DependencyInfo dep : pomInfo.managedDependencies()) {
            VersionType type = dep.versionType() == VersionType.DIRECT ? VersionType.MANAGED : dep.versionType();
            updatedPom = checkAndBump(dep, type, latestVersions, updatedByMap, repo, updatedPom, bumps);
        }

        if (bumps.isEmpty()) {
            log.info("{}/{} ({}) is up to date", repo.getOwner(), repo.getName(), branch.getName());
            return 0;
        }

        log.info("{}/{} ({}) needs {} update(s):", repo.getOwner(), repo.getName(), branch.getName(), bumps.size());
        for (BumpedDependency bump : bumps) {
            log.info("  - {}:{} {} -> {}", bump.groupId(), bump.artifactId(), bump.oldVersion(), bump.newVersion());
        }

        if (branch.isAutoMerge()) {
            log.info("Auto-merging to {}/{} ({})", repo.getOwner(), repo.getName(), branch.getName());
            prCreator.directCommit(repo.getOwner(), repo.getName(), repo.getPomPath(), branch.getName(), updatedPom, bumps);
        } else {
            prCreator.createUpdatePr(repo.getOwner(), repo.getName(), repo.getPomPath(), branch.getName(), updatedPom, bumps, false);
        }
        return 1;
    }

    private String checkAndBump(DependencyInfo dep, VersionType versionType, Map<String, String> latestVersions,
                                Map<String, String> updatedByMap, RepoConfig repo,
                                String pomContent, List<BumpedDependency> bumps) {
        String key = dep.groupId() + ":" + dep.artifactId();
        String latestVersion = latestVersions.get(key);

        if (latestVersion == null || dep.resolvedVersion() == null
                || !VersionComparator.isOlderThan(dep.resolvedVersion(), latestVersion)) {
            return pomContent;
        }

        DependencyMatch match = DependencyMatch.builder()
                .groupId(dep.groupId())
                .artifactId(dep.artifactId())
                .currentVersion(dep.resolvedVersion())
                .versionType(versionType)
                .propertyKey(dep.propertyKey())
                .repoOwner(repo.getOwner())
                .repoName(repo.getName())
                .pomPath(repo.getPomPath())
                .build();

        String updatedBy = updatedByMap.getOrDefault(key, "unknown");

        String updatedPom = pomModifier.updateVersion(pomContent, match, latestVersion);
        if (!updatedPom.equals(pomContent)) {
            bumps.add(new BumpedDependency(dep.groupId(), dep.artifactId(), dep.resolvedVersion(), latestVersion, updatedBy));
        }
        return updatedPom;
    }

    private String fetchPom(String owner, String repo, String pomPath, String branch) throws Exception {
        GitHubClient.FileContent file = gitHubClient.getFileContent(owner, repo, pomPath, branch);
        return file.content();
    }
}
