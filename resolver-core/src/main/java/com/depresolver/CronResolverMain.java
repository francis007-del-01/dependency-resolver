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
import com.depresolver.registry.VersionComparator;
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
            RegistryClient registryClient = new RegistryClient(gitHubClient);
            PomParser pomParser = new PomParser();
            PomModifier pomModifier = new PomModifier();
            PullRequestCreator prCreator = new PullRequestCreator(gitHubClient, branchPrefix);

            Map<String, String> latestVersions = loadLatestVersions(registryClient);
            Map<String, ArtifactEntry> artifactMetadata = loadArtifactMetadata(registryClient);
            List<String> pomArtifacts = registryClient.listAllPomArtifacts();
            log.info("Found {} artifacts with poms in registry", pomArtifacts.size());

            int[] counts = processArtifacts(pomArtifacts, artifactMetadata, latestVersions,
                    registryClient, pomParser, pomModifier, prCreator);

            log.info("Done. PRs created: {}, Skipped: {}, Failed: {}", counts[0], counts[1], counts[2]);
            return counts[2] > 0 ? 1 : 0;

        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            return 2;
        }
    }

    private Map<String, String> loadLatestVersions(RegistryClient registryClient) throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        for (ArtifactEntry a : registryClient.readRegistry().getArtifacts()) {
            latestVersions.put(a.getGroupId() + ":" + a.getArtifactId(), a.getLatestVersion());
        }
        log.info("Loaded {} artifact versions from registry", latestVersions.size());
        return latestVersions;
    }

    private Map<String, ArtifactEntry> loadArtifactMetadata(RegistryClient registryClient) throws Exception {
        Map<String, ArtifactEntry> metadata = new HashMap<>();
        for (ArtifactEntry a : registryClient.readRegistry().getArtifacts()) {
            metadata.put(a.getGroupId() + ":" + a.getArtifactId(), a);
        }
        return metadata;
    }

    private int[] processArtifacts(List<String> pomArtifacts, Map<String, ArtifactEntry> artifactMetadata,
                                   Map<String, String> latestVersions, RegistryClient registryClient,
                                   PomParser pomParser, PomModifier pomModifier, PullRequestCreator prCreator) {
        int created = 0, skipped = 0, failed = 0;

        for (String pomArtifactKey : pomArtifacts) {
            String[] parts = pomArtifactKey.split(":");
            String groupId = parts[0];
            String artifactId = parts[1];

            ArtifactEntry metadata = artifactMetadata.get(pomArtifactKey);
            if (metadata == null) {
                log.warn("No metadata in versions.yaml for {}:{}, skipping", groupId, artifactId);
                skipped++;
                continue;
            }

            List<String> branches = registryClient.listBranches(groupId, artifactId);
            for (String branch : branches) {
                try {
                    int result = processBranch(groupId, artifactId, branch, metadata,
                            latestVersions, registryClient, pomParser, pomModifier, prCreator);
                    if (result > 0) created++;
                    else skipped++;
                } catch (Exception e) {
                    log.error("Failed to process {}:{} ({}): {}", groupId, artifactId, branch, e.getMessage());
                    failed++;
                }
            }
        }
        return new int[]{created, skipped, failed};
    }

    private int processBranch(String groupId, String artifactId, String branch, ArtifactEntry metadata,
                              Map<String, String> latestVersions, RegistryClient registryClient,
                              PomParser pomParser, PomModifier pomModifier, PullRequestCreator prCreator) throws Exception {
        log.debug("Processing {}:{} on branch {}", groupId, artifactId, branch);

        String pomContent;
        try {
            pomContent = registryClient.readPom(groupId, artifactId, branch);
        } catch (Exception e) {
            log.warn("Could not read pom for {}:{} ({}), skipping", groupId, artifactId, branch);
            return 0;
        }

        PomInfo pomInfo = pomParser.parse(pomContent);
        List<BumpedDependency> bumps = new ArrayList<>();
        String updatedPom = pomContent;

        // Scan direct + managed dependencies
        for (DependencyInfo dep : pomInfo.dependencies()) {
            updatedPom = checkAndBump(dep, dep.versionType(), latestVersions, metadata, pomModifier, updatedPom, bumps);
        }
        for (DependencyInfo dep : pomInfo.managedDependencies()) {
            VersionType type = dep.versionType() == VersionType.DIRECT ? VersionType.MANAGED : dep.versionType();
            updatedPom = checkAndBump(dep, type, latestVersions, metadata, pomModifier, updatedPom, bumps);
        }

        if (bumps.isEmpty()) {
            log.debug("{}:{} ({}) is up to date", groupId, artifactId, branch);
            return 0;
        }

        log.info("{}:{} ({}) needs {} dependency update(s)", groupId, artifactId, branch, bumps.size());
        GitHubClient.PrResult result = prCreator.createUpdatePr(metadata, branch, updatedPom, bumps, dryRun);
        return result != null ? 1 : 0;
    }

    private String checkAndBump(DependencyInfo dep, VersionType versionType, Map<String, String> latestVersions,
                                ArtifactEntry metadata, PomModifier pomModifier, String pomContent,
                                List<BumpedDependency> bumps) {
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
                .repoOwner(metadata.getRepoOwner())
                .repoName(metadata.getRepoName())
                .pomPath(metadata.getPomPath())
                .build();

        bumps.add(new BumpedDependency(dep.groupId(), dep.artifactId(), dep.resolvedVersion(), latestVersion));
        return pomModifier.updateVersion(pomContent, match, latestVersion);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CronResolverMain()).execute(args);
        System.exit(exitCode);
    }
}
