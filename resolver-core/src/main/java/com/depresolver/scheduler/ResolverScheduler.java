package com.depresolver.scheduler;

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
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ResolverScheduler implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ResolverScheduler.class);

    private final RegistryClient registryClient;
    private final PomParser pomParser;
    private final PomModifier pomModifier;
    private final PullRequestCreator prCreator;

    public ResolverScheduler(RegistryClient registryClient, PomParser pomParser,
                             PomModifier pomModifier, PullRequestCreator prCreator) {
        this.registryClient = registryClient;
        this.pomParser = pomParser;
        this.pomModifier = pomModifier;
        this.prCreator = prCreator;
    }

    @Override
    public void run(String... args) {
        try {
            log.info("Cron Resolver starting...");

            Map<String, String> latestVersions = loadLatestVersions();
            Map<String, ArtifactEntry> artifactMetadata = loadArtifactMetadata();
            List<String> pomArtifacts = registryClient.listAllPomArtifacts();
            log.info("Found {} artifacts with poms in registry", pomArtifacts.size());

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
                        int result = processBranch(groupId, artifactId, branch, metadata, latestVersions);
                        if (result > 0) created++;
                        else skipped++;
                    } catch (Exception e) {
                        log.error("Failed to process {}:{} ({}): {}", groupId, artifactId, branch, e.getMessage());
                        failed++;
                    }
                }
            }

            log.info("Done. PRs created: {}, Skipped: {}, Failed: {}", created, skipped, failed);
        } catch (Exception e) {
            log.error("Fatal error in resolver: {}", e.getMessage(), e);
        }
    }

    private Map<String, String> loadLatestVersions() throws Exception {
        Map<String, String> latestVersions = new HashMap<>();
        for (ArtifactEntry a : registryClient.readRegistry().getArtifacts()) {
            latestVersions.put(a.getGroupId() + ":" + a.getArtifactId(), a.getLatestVersion());
        }
        log.info("Loaded {} artifact versions from registry", latestVersions.size());
        return latestVersions;
    }

    private Map<String, ArtifactEntry> loadArtifactMetadata() throws Exception {
        Map<String, ArtifactEntry> metadata = new HashMap<>();
        for (ArtifactEntry a : registryClient.readRegistry().getArtifacts()) {
            metadata.put(a.getGroupId() + ":" + a.getArtifactId(), a);
        }
        return metadata;
    }

    private int processBranch(String groupId, String artifactId, String branch,
                              ArtifactEntry metadata, Map<String, String> latestVersions) throws Exception {
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

        for (DependencyInfo dep : pomInfo.dependencies()) {
            updatedPom = checkAndBump(dep, dep.versionType(), latestVersions, metadata, updatedPom, bumps);
        }
        for (DependencyInfo dep : pomInfo.managedDependencies()) {
            VersionType type = dep.versionType() == VersionType.DIRECT ? VersionType.MANAGED : dep.versionType();
            updatedPom = checkAndBump(dep, type, latestVersions, metadata, updatedPom, bumps);
        }

        if (pomInfo.parentDependency() != null) {
            VersionType type = pomInfo.parentDependency().versionType() == VersionType.DIRECT
                    ? VersionType.PARENT : pomInfo.parentDependency().versionType();
            updatedPom = checkAndBump(pomInfo.parentDependency(), type, latestVersions, metadata, updatedPom, bumps);
        }

        for (DependencyInfo plugin : pomInfo.plugins()) {
            VersionType type = plugin.versionType() == VersionType.DIRECT
                    ? VersionType.PLUGIN : plugin.versionType();
            updatedPom = checkAndBump(plugin, type, latestVersions, metadata, updatedPom, bumps);
        }
        for (DependencyInfo plugin : pomInfo.managedPlugins()) {
            VersionType type = plugin.versionType() == VersionType.DIRECT
                    ? VersionType.PLUGIN : plugin.versionType();
            updatedPom = checkAndBump(plugin, type, latestVersions, metadata, updatedPom, bumps);
        }

        if (bumps.isEmpty()) {
            log.debug("{}:{} ({}) is up to date", groupId, artifactId, branch);
            return 0;
        }

        log.info("{}:{} ({}) needs {} dependency update(s)", groupId, artifactId, branch, bumps.size());
        GitHubClient.PrResult result = prCreator.createUpdatePr(metadata, branch, updatedPom, bumps, false);
        return result != null ? 1 : 0;
    }

    private String checkAndBump(DependencyInfo dep, VersionType versionType, Map<String, String> latestVersions,
                                ArtifactEntry metadata, String pomContent, List<BumpedDependency> bumps) {
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

        String updatedPom = pomModifier.updateVersion(pomContent, match, latestVersion);
        if (!updatedPom.equals(pomContent)) {
            bumps.add(new BumpedDependency(dep.groupId(), dep.artifactId(), dep.resolvedVersion(), latestVersion));
        }
        return updatedPom;
    }
}
