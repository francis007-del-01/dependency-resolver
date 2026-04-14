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

import com.depresolver.version.VersionComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ResolverScheduler implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ResolverScheduler.class);

    private final ResolverConfig resolverConfig;
    private final GitHubClient gitHubClient;
    private final PomParser pomParser;
    private final PomModifier pomModifier;
    private final PullRequestCreator prCreator;

    @Value("${resolver.parallelism:10}")
    private int parallelism = 10;

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
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            log.info("Dependency Resolver starting (parallelism={})", parallelism);

            // Phase 1: Build latest versions map from trigger branches (parallel)
            Map<String, String> latestVersions = new ConcurrentHashMap<>();
            Map<String, String> updatedByMap = new ConcurrentHashMap<>();

            List<CompletableFuture<Void>> triggerFutures = new ArrayList<>();
            for (RepoConfig repo : resolverConfig.getRepos()) {
                if (repo.getTriggerBranch() == null) continue;

                triggerFutures.add(CompletableFuture.runAsync(() -> {
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
                }, executor));
            }

            // Wait for all triggers to complete
            CompletableFuture.allOf(triggerFutures.toArray(new CompletableFuture[0])).join();
            log.info("Loaded {} artifact versions from trigger branches", latestVersions.size());

            // Phase 2: Process target branches (parallel)
            AtomicInteger created = new AtomicInteger();
            AtomicInteger skipped = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();

            List<CompletableFuture<Void>> targetFutures = new ArrayList<>();
            for (RepoConfig repo : resolverConfig.getRepos()) {
                if (repo.getTargetBranches() == null || repo.getTargetBranches().isEmpty()) continue;

                for (BranchConfig branch : repo.getTargetBranches()) {
                    targetFutures.add(CompletableFuture.runAsync(() -> {
                        try {
                            int result = processBranch(repo, branch, latestVersions, updatedByMap);
                            if (result > 0) created.incrementAndGet();
                            else skipped.incrementAndGet();
                        } catch (Exception e) {
                            log.error("Failed {}/{} ({}): {}", repo.getOwner(), repo.getName(), branch.getName(), e.getMessage());
                            failed.incrementAndGet();
                        }
                    }, executor));
                }
            }

            // Wait for all targets to complete
            CompletableFuture.allOf(targetFutures.toArray(new CompletableFuture[0])).join();

            log.info("Done. Created: {}, Skipped: {}, Failed: {}", created.get(), skipped.get(), failed.get());
        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
        } finally {
            executor.shutdown();
        }
    }

    private int processBranch(RepoConfig repo, BranchConfig branch, Map<String, String> latestVersions,
                              Map<String, String> updatedByMap) throws Exception {
        log.info("Processing {}/{} on branch {}", repo.getOwner(), repo.getName(), branch.getName());

        String pomContent = fetchPom(repo.getOwner(), repo.getName(), repo.getPomPath(), branch.getName());
        PomInfo pomInfo = pomParser.parse(pomContent);

        // Find what needs updating
        List<BumpedDependency> bumps = findBumps(pomInfo.dependencies(), latestVersions, updatedByMap);
        bumps.addAll(findBumps(pomInfo.managedDependencies(), latestVersions, updatedByMap));

        if (bumps.isEmpty()) {
            log.info("{}/{} ({}) is up to date", repo.getOwner(), repo.getName(), branch.getName());
            return 0;
        }

        log.info("{}/{} ({}) needs {} update(s):", repo.getOwner(), repo.getName(), branch.getName(), bumps.size());
        for (BumpedDependency bump : bumps) {
            log.info("  - {}:{} {} -> {}", bump.groupId(), bump.artifactId(), bump.oldVersion(), bump.newVersion());
        }

        // Apply updates to pom
        String updatedPom = applyBumps(pomContent, bumps, repo);

        if (branch.isAutoMerge()) {
            log.info("Auto-merging to {}/{} ({})", repo.getOwner(), repo.getName(), branch.getName());
            prCreator.directCommit(repo.getOwner(), repo.getName(), repo.getPomPath(), branch.getName(), updatedPom, bumps);
        } else {
            prCreator.createUpdatePr(repo.getOwner(), repo.getName(), repo.getPomPath(), branch.getName(), updatedPom, bumps, false);
        }
        return 1;
    }

    private List<BumpedDependency> findBumps(List<DependencyInfo> deps, Map<String, String> latestVersions,
                                              Map<String, String> updatedByMap) {
        List<BumpedDependency> bumps = new ArrayList<>();
        for (DependencyInfo dep : deps) {
            String key = dep.groupId() + ":" + dep.artifactId();
            String latestVersion = latestVersions.get(key);

            if (latestVersion != null && dep.resolvedVersion() != null
                    && VersionComparator.isOlderThan(dep.resolvedVersion(), latestVersion)) {
                String updatedBy = updatedByMap.getOrDefault(key, "unknown");
                bumps.add(new BumpedDependency(dep.groupId(), dep.artifactId(), dep.resolvedVersion(), latestVersion, updatedBy, dep.versionType(), dep.propertyKey()));
            }
        }
        return bumps;
    }

    private String applyBumps(String pomContent, List<BumpedDependency> bumps, RepoConfig repo) {
        String updatedPom = pomContent;
        for (BumpedDependency bump : bumps) {
            DependencyMatch match = DependencyMatch.builder()
                    .groupId(bump.groupId())
                    .artifactId(bump.artifactId())
                    .currentVersion(bump.oldVersion())
                    .versionType(bump.versionType())
                    .propertyKey(bump.propertyKey())
                    .repoOwner(repo.getOwner())
                    .repoName(repo.getName())
                    .pomPath(repo.getPomPath())
                    .build();
            updatedPom = pomModifier.updateVersion(updatedPom, match, bump.newVersion());
        }
        return updatedPom;
    }

    private String fetchPom(String owner, String repo, String pomPath, String branch) throws Exception {
        GitHubClient.FileContent file = gitHubClient.getFileContent(owner, repo, pomPath, branch);
        return file.content();
    }
}
