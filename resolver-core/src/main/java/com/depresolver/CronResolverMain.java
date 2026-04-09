package com.depresolver;

import com.depresolver.github.GitHubClient;
import com.depresolver.github.PullRequestCreator;
import com.depresolver.github.PullRequestCreator.BumpedDependency;
import com.depresolver.pom.PomModifier;
import com.depresolver.pom.PomParser;
import com.depresolver.pom.PomParser.DependencyInfo;
import com.depresolver.pom.PomParser.PomInfo;
import com.depresolver.registry.ArtifactEntry;
import com.depresolver.registry.RegistryClient;
import com.depresolver.registry.RegistryClient.RegistrySnapshot;
import com.depresolver.scanner.DependencyMatch;
import com.depresolver.scanner.DependencyMatch.VersionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "cron-resolver",
        mixinStandardHelpOptions = true,
        version = "2.0.0",
        description = "Reads a version registry and creates PRs to update outdated dependencies"
)
public class CronResolverMain implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CronResolverMain.class);

    @Option(names = {"-t", "--github-token"}, required = true, description = "GitHub personal access token")
    private String githubToken;

    @Option(names = {"--registry-branch"}, defaultValue = "main", description = "Branch of the registry repo")
    private String registryBranch;

    @Option(names = {"--branch-prefix"}, defaultValue = "deps", description = "Prefix for PR branches")
    private String branchPrefix;

    @Option(names = {"--dry-run"}, description = "Log what would happen without creating PRs")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            log.info("Cron Resolver starting...");
            if (dryRun) log.info("DRY RUN mode enabled");

            GitHubClient gitHubClient = new GitHubClient(githubToken);
            RegistryClient registryClient = new RegistryClient(gitHubClient, registryBranch);
            PomParser pomParser = new PomParser();
            PomModifier pomModifier = new PomModifier();
            PullRequestCreator prCreator = new PullRequestCreator(gitHubClient, branchPrefix);

            // 1. Read the version registry
            RegistrySnapshot snapshot = registryClient.readRegistryWithSha();
            List<ArtifactEntry> artifacts = snapshot.registry().getArtifacts();

            if (artifacts.isEmpty()) {
                log.info("Registry is empty. Nothing to do.");
                return 0;
            }
            log.info("Registry contains {} artifacts", artifacts.size());

            // 2. Build lookup map: groupId:artifactId -> latestVersion
            Map<String, String> latestVersions = new HashMap<>();
            for (ArtifactEntry artifact : artifacts) {
                latestVersions.put(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact.getLatestVersion());
            }

            // 3. Process each registered artifact
            int created = 0;
            int skipped = 0;
            int failed = 0;

            for (ArtifactEntry artifact : artifacts) {
                List<String> branches = artifact.getTargetBranches();
                if (branches == null || branches.isEmpty()) {
                    log.warn("No target branches for {}:{}, skipping", artifact.getGroupId(), artifact.getArtifactId());
                    skipped++;
                    continue;
                }

                for (String targetBranch : branches) {
                    try {
                        log.debug("Processing {}:{} on branch {}", artifact.getGroupId(), artifact.getArtifactId(), targetBranch);

                        // Read this artifact's pom from registry for this branch
                        String pomContent;
                        try {
                            pomContent = registryClient.readPom(artifact.getGroupId(), artifact.getArtifactId(), targetBranch);
                        } catch (Exception e) {
                            log.warn("No pom found in registry for {}:{} ({}), skipping", artifact.getGroupId(), artifact.getArtifactId(), targetBranch);
                            skipped++;
                            continue;
                        }

                        // Parse the pom and find dependencies that are also in the registry
                        PomInfo pomInfo = pomParser.parse(pomContent);
                        List<BumpedDependency> bumps = new ArrayList<>();
                        String updatedPom = pomContent;

                        for (DependencyInfo dep : pomInfo.dependencies()) {
                            String key = dep.groupId() + ":" + dep.artifactId();
                            String latestVersion = latestVersions.get(key);
                            if (latestVersion != null && dep.resolvedVersion() != null
                                    && !dep.resolvedVersion().equals(latestVersion)) {
                                DependencyMatch match = DependencyMatch.builder()
                                        .groupId(dep.groupId())
                                        .artifactId(dep.artifactId())
                                        .currentVersion(dep.resolvedVersion())
                                        .versionType(dep.versionType())
                                        .propertyKey(dep.propertyKey())
                                        .repoOwner(artifact.getRepoOwner())
                                        .repoName(artifact.getRepoName())
                                        .pomPath(artifact.getPomPath())
                                        .build();
                                updatedPom = pomModifier.updateVersion(updatedPom, match, latestVersion);
                                bumps.add(new BumpedDependency(dep.groupId(), dep.artifactId(), dep.resolvedVersion(), latestVersion));
                            }
                        }

                        // Also check managed dependencies
                        for (DependencyInfo dep : pomInfo.managedDependencies()) {
                            String key = dep.groupId() + ":" + dep.artifactId();
                            String latestVersion = latestVersions.get(key);
                            if (latestVersion != null && dep.resolvedVersion() != null
                                    && !dep.resolvedVersion().equals(latestVersion)) {
                                VersionType type = dep.versionType() == VersionType.DIRECT ? VersionType.MANAGED : dep.versionType();
                                DependencyMatch match = DependencyMatch.builder()
                                        .groupId(dep.groupId())
                                        .artifactId(dep.artifactId())
                                        .currentVersion(dep.resolvedVersion())
                                        .versionType(type)
                                        .propertyKey(dep.propertyKey())
                                        .repoOwner(artifact.getRepoOwner())
                                        .repoName(artifact.getRepoName())
                                        .pomPath(artifact.getPomPath())
                                        .build();
                                updatedPom = pomModifier.updateVersion(updatedPom, match, latestVersion);
                                bumps.add(new BumpedDependency(dep.groupId(), dep.artifactId(), dep.resolvedVersion(), latestVersion));
                            }
                        }

                        if (bumps.isEmpty()) {
                            log.debug("{}:{} ({}) is up to date", artifact.getGroupId(), artifact.getArtifactId(), targetBranch);
                            skipped++;
                            continue;
                        }

                        log.info("{}:{} ({}) needs {} dependency update(s)", artifact.getGroupId(), artifact.getArtifactId(), targetBranch, bumps.size());

                        // Create PR targeting this branch
                        GitHubClient.PrResult result = prCreator.createUpdatePr(artifact, targetBranch, updatedPom, bumps, dryRun);
                        if (result != null) {
                            created++;
                        } else {
                            skipped++;
                        }

                    } catch (Exception e) {
                        log.error("Failed to process {}:{} ({}): {}", artifact.getGroupId(), artifact.getArtifactId(), targetBranch, e.getMessage());
                        failed++;
                    }
                }
            }

            log.info("Done. PRs created: {}, Skipped: {}, Failed: {}", created, skipped, failed);
            return failed > 0 ? 1 : 0;

        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            return 2;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CronResolverMain()).execute(args);
        System.exit(exitCode);
    }
}
