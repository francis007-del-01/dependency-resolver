package com.depresolver.gate;

import com.depresolver.config.RepoConfig;
import com.depresolver.config.ServiceUserProperties;
import com.depresolver.github.GitHubClient;
import com.depresolver.github.GitHubClient.CommitAuthor;
import com.depresolver.version.TagVersionSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class SnapshotGateService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotGateService.class);

    private static final List<String> SCAN_BRANCHES = List.of("master", "develop");

    private final GitHubClient gitHubClient;
    private final ServiceUserProperties serviceUserProperties;

    public SnapshotGateService(GitHubClient gitHubClient, ServiceUserProperties serviceUserProperties) {
        this.gitHubClient = gitHubClient;
        this.serviceUserProperties = serviceUserProperties;
    }

    public String resolveEffectiveVersion(RepoConfig repo, String pomVersion) {
        if (pomVersion == null || !pomVersion.endsWith("-SNAPSHOT")) {
            return pomVersion;
        }

        String owner = repo.getOwner();
        String name = repo.getName();

        Optional<String> tagOpt;
        try {
            tagOpt = TagVersionSelector.latestReleaseTag(gitHubClient.listTags(owner, name));
        } catch (Exception e) {
            log.warn("gate: {}/{} could not list tags ({}); propagating {}", owner, name, e.getMessage(), pomVersion);
            return pomVersion;
        }

        if (tagOpt.isEmpty()) {
            log.warn("gate: {}/{} no release tag (vX.Y.Z.W) found; propagating {}", owner, name, pomVersion);
            return pomVersion;
        }

        String tagName = tagOpt.get();
        Set<String> branches = new LinkedHashSet<>();
        if (repo.getTriggerBranch() != null) branches.add(repo.getTriggerBranch());
        branches.addAll(SCAN_BRANCHES);

        int totalNonService = 0;
        StringBuilder perBranch = new StringBuilder();
        for (String branch : branches) {
            int nonService = countNonServiceCommits(owner, name, tagName, branch);
            if (nonService < 0) {
                perBranch.append(branch).append("=skipped ");
            } else {
                perBranch.append(branch).append("=").append(nonService).append(" ");
                totalNonService += nonService;
            }
        }

        if (totalNonService >= 1) {
            log.info("gate: {}/{} propagating {} ({} non-service commits since {}; {})",
                    owner, name, pomVersion, totalNonService, tagName, perBranch.toString().trim());
            return pomVersion;
        }

        String releaseVersion = TagVersionSelector.stripLeadingV(tagName);
        log.info("gate: {}/{} SNAPSHOT {} replaced with release {} (tag {}, 0 non-service commits; {})",
                owner, name, pomVersion, releaseVersion, tagName, perBranch.toString().trim());
        return releaseVersion;
    }

    private int countNonServiceCommits(String owner, String name, String base, String head) {
        try {
            List<CommitAuthor> commits = gitHubClient.compareCommits(owner, name, base, head);
            int count = 0;
            for (CommitAuthor author : commits) {
                if (!serviceUserProperties.isServiceUser(author.name(), author.email())) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.debug("gate: {}/{} compare {}...{} failed: {}", owner, name, base, head, e.getMessage());
            return -1;
        }
    }
}
