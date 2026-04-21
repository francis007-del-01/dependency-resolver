package com.depresolver.scheduler;

import com.depresolver.config.BranchConfig;
import com.depresolver.config.RepoConfig;
import com.depresolver.config.ResolverConfig;
import com.depresolver.gate.SnapshotGateService;
import com.depresolver.github.GitHubClient;
import com.depresolver.github.PullRequestCreator;
import com.depresolver.github.PullRequestCreator.BumpedDependency;
import com.depresolver.pom.PomManager;
import com.depresolver.pom.PomManager.PomCoordinates;
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
    private final PullRequestCreator prCreator;
    private final PomManager pomManager;
    private final SnapshotGateService snapshotGate;

    @Value("${resolver.parallelism:10}")
    private int parallelism = 10;

    public ResolverScheduler(ResolverConfig resolverConfig, GitHubClient gitHubClient,
                             PullRequestCreator prCreator, PomManager pomManager,
                             SnapshotGateService snapshotGate) {
        this.resolverConfig = resolverConfig;
        this.gitHubClient = gitHubClient;
        this.prCreator = prCreator;
        this.pomManager = pomManager;
        this.snapshotGate = snapshotGate;
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
                        String pomContent = fetchPom(repo, repo.getTriggerBranch());
                        PomCoordinates coords = pomManager.readCoordinates(pomContent);

                        if (coords.groupId() != null && coords.artifactId() != null && coords.version() != null) {
                            String key = coords.groupId() + ":" + coords.artifactId();
                            String effective = snapshotGate.resolveEffectiveVersion(repo, coords.version());
                            latestVersions.put(key, effective);

                            String committer = gitHubClient.getLastCommitter(repo.getOwner(), repo.getName(), repo.getTriggerBranch());
                            if (committer != null) updatedByMap.put(key, committer);

                            if (!effective.equals(coords.version())) {
                                log.info("  {} = {} (gated from {}, from {}, by {})", key, effective,
                                        coords.version(), repo.getUrl(), committer != null ? committer : "unknown");
                            } else {
                                log.info("  {} = {} (from {}, by {})", key, effective,
                                        repo.getUrl(), committer != null ? committer : "unknown");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Could not read trigger version from {}: {}", repo.getUrl(), e.getMessage());
                    }
                }, executor));
            }

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
                            log.error("Failed {} ({}): {}", repo.getUrl(), branch.getName(), e.getMessage());
                            failed.incrementAndGet();
                        }
                    }, executor));
                }
            }

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
        log.info("Processing {} on branch {}", repo.getUrl(), branch.getName());

        String pomContent = fetchPom(repo, branch.getName());
        List<BumpedDependency> bumps = pomManager.findBumps(pomContent, latestVersions, updatedByMap);

        if (bumps.isEmpty()) {
            log.info("{} ({}) is up to date", repo.getUrl(), branch.getName());
            return 0;
        }

        log.info("{} ({}) needs {} update(s):", repo.getUrl(), branch.getName(), bumps.size());
        for (BumpedDependency bump : bumps) {
            log.info("  - {}:{} {} -> {}", bump.groupId(), bump.artifactId(), bump.oldVersion(), bump.newVersion());
        }

        String updatedPom = pomManager.applyBumps(pomContent, bumps);

        if (branch.isAutoMerge()) {
            log.info("Auto-merging to {} ({})", repo.getUrl(), branch.getName());
            prCreator.directCommit(repo.getOwner(), repo.getName(), repo.getPomPath(), branch.getName(), updatedPom, bumps);
        } else {
            prCreator.createUpdatePr(repo.getOwner(), repo.getName(), repo.getPomPath(), branch.getName(), updatedPom, bumps, false);
        }
        return 1;
    }

    private String fetchPom(RepoConfig repo, String branch) throws Exception {
        GitHubClient.FileContent file = gitHubClient.getFileContent(repo.getOwner(), repo.getName(), repo.getPomPath(), branch);
        return file.content();
    }
}
