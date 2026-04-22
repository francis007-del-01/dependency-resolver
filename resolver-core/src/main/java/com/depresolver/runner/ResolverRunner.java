package com.depresolver.runner;

import com.depresolver.artifactory.ArtifactoryClient;
import com.depresolver.artifactory.GitPropertiesExtractor.GitInfo;
import com.depresolver.artifactory.JarScmExtractor;
import com.depresolver.config.ServiceUserProperties;
import com.depresolver.github.GitHubClient;
import com.depresolver.github.GitHubClient.CommitAuthor;
import com.depresolver.pom.BumpedDependency;
import com.depresolver.pom.PomManager;
import com.depresolver.pom.PomManager.FetchDirective;
import com.depresolver.pom.PomManager.FetchDirectives;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ResolverRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ResolverRunner.class);

    private final GitHubClient gitHubClient;
    private final ArtifactoryClient artifactoryClient;
    private final PomManager pomManager;
    private final ServiceUserProperties serviceUserProperties;

    public ResolverRunner(GitHubClient gitHubClient, ArtifactoryClient artifactoryClient,
                          PomManager pomManager, ServiceUserProperties serviceUserProperties) {
        this.gitHubClient = gitHubClient;
        this.artifactoryClient = artifactoryClient;
        this.pomManager = pomManager;
        this.serviceUserProperties = serviceUserProperties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String owner = requireArg(args, "owner");
        String repo = requireArg(args, "repo");
        String branch = requireArg(args, "branch");
        String pomPath = firstArg(args, "pomPath", "pom.xml");

        log.info("Resolving dependencies for {}/{} on branch {} (pom: {})", owner, repo, branch, pomPath);

        GitHubClient.FileContent pomFile = gitHubClient.getFileContent(owner, repo, pomPath, branch);
        String pomContent = pomFile.content();

        FetchDirectives directives = pomManager.readFetchDirectives(pomContent);
        log.info("fetchLatest: {} deps, fetchRelease: {} deps", directives.latest().size(), directives.release().size());

        if (directives.latest().isEmpty() && directives.release().isEmpty()) {
            log.info("No <fetchLatest> or <fetchRelease> directives; nothing to do");
            return;
        }

        Map<String, String> latestVersions = new HashMap<>();
        for (FetchDirective d : directives.latest()) {
            resolveLatest(d).ifPresent(v -> {
                latestVersions.put(d.key(), v);
                log.info("  fetchLatest {} -> {}", d.key(), v);
            });
        }
        for (FetchDirective d : directives.release()) {
            Optional<String> v = artifactoryClient.latestReleaseVersion(d.groupId(), d.artifactId());
            if (v.isPresent()) {
                latestVersions.put(d.key(), v.get());
                log.info("  fetchRelease {} -> {}", d.key(), v.get());
            } else {
                log.warn("  fetchRelease {} -> no release found in Artifactory", d.key());
            }
        }

        List<BumpedDependency> bumps = pomManager.findBumpsFromDirectives(pomContent, latestVersions, new HashMap<>());

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

        String message = buildCommitMessage(bumps);
        gitHubClient.updateFile(owner, repo, pomPath, updated, pomFile.sha(), branch, message);
        log.info("Committed {} update(s) to {}/{} on {}", bumps.size(), owner, repo, branch);
    }

    private Optional<String> resolveLatest(FetchDirective d) throws Exception {
        String g = d.groupId();
        String a = d.artifactId();

        Optional<String> releaseVersion = artifactoryClient.latestReleaseVersion(g, a);
        Optional<String> snapshotVersion = artifactoryClient.latestSnapshotBaseVersion(g, a);

        if (snapshotVersion.isEmpty() && releaseVersion.isEmpty()) {
            log.warn("  fetchLatest {} -> no release or SNAPSHOT in Artifactory", d.key());
            return Optional.empty();
        }
        if (snapshotVersion.isEmpty()) {
            log.info("  fetchLatest {} -> no SNAPSHOT; using release", d.key());
            return releaseVersion;
        }
        if (releaseVersion.isEmpty()) {
            log.info("  fetchLatest {} -> no release yet; using SNAPSHOT", d.key());
            return snapshotVersion;
        }

        Optional<GitInfo> snapInfo;
        Optional<GitInfo> relInfo;
        try {
            snapInfo = artifactoryClient.getSnapshotGitInfo(g, a, snapshotVersion.get());
            relInfo = artifactoryClient.getReleaseGitInfo(g, a, releaseVersion.get());
        } catch (Exception e) {
            log.warn("  fetchLatest {} -> git info fetch failed ({}); preferring SNAPSHOT",
                    d.key(), e.getMessage());
            return snapshotVersion;
        }

        if (snapInfo.isEmpty() || relInfo.isEmpty()) {
            log.warn("  fetchLatest {} -> git.properties missing (snapshot={} release={}); preferring SNAPSHOT",
                    d.key(), snapInfo.isPresent(), relInfo.isPresent());
            return snapshotVersion;
        }

        if (snapInfo.get().dirty() || relInfo.get().dirty()) {
            log.warn("  fetchLatest {} -> dirty build detected (snapshotDirty={} releaseDirty={}); preferring SNAPSHOT",
                    d.key(), snapInfo.get().dirty(), relInfo.get().dirty());
            return snapshotVersion;
        }

        String snapSha = snapInfo.get().commitSha();
        String relSha = relInfo.get().commitSha();
        if (snapSha.equals(relSha)) {
            log.info("  fetchLatest {} -> SNAPSHOT and release built from same sha ({}); using release",
                    d.key(), abbrev(snapSha));
            return releaseVersion;
        }

        Optional<JarScmExtractor.GitHubCoords> scmOpt;
        try {
            scmOpt = artifactoryClient.getReleaseScm(g, a, releaseVersion.get());
        } catch (Exception e) {
            log.warn("  fetchLatest {} -> SCM fetch failed ({}); preferring SNAPSHOT",
                    d.key(), e.getMessage());
            return snapshotVersion;
        }
        if (scmOpt.isEmpty()) {
            log.warn("  fetchLatest {} -> no <scm> in library pom; can't filter bot commits, preferring SNAPSHOT",
                    d.key());
            return snapshotVersion;
        }
        JarScmExtractor.GitHubCoords scm = scmOpt.get();

        List<CommitAuthor> commits;
        try {
            commits = gitHubClient.compareCommits(scm.owner(), scm.name(), relSha, snapSha);
        } catch (Exception e) {
            log.warn("  fetchLatest {} -> GitHub compare {}/{} {}..{} failed ({}); preferring SNAPSHOT",
                    d.key(), scm.owner(), scm.name(), abbrev(relSha), abbrev(snapSha), e.getMessage());
            return snapshotVersion;
        }

        int human = 0;
        for (CommitAuthor ca : commits) {
            if (!serviceUserProperties.isServiceUser(ca.name(), ca.email())) human++;
        }

        if (human == 0) {
            log.info("  fetchLatest {} -> {} bot-only commits between release {} and snapshot {}; using release",
                    d.key(), commits.size(), abbrev(relSha), abbrev(snapSha));
            return releaseVersion;
        }
        log.info("  fetchLatest {} -> {} human commit(s) between release {} and snapshot {}; using SNAPSHOT",
                d.key(), human, abbrev(relSha), abbrev(snapSha));
        return snapshotVersion;
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

    private static String abbrev(String sha) {
        if (sha == null) return "?";
        return sha.length() >= 7 ? sha.substring(0, 7) : sha;
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
}
