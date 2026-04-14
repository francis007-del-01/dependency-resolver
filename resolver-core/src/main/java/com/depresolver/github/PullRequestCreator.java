package com.depresolver.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class PullRequestCreator {

    private static final Logger log = LoggerFactory.getLogger(PullRequestCreator.class);

    private final GitHubClient gitHubClient;
    private final String branchPrefix;

    public PullRequestCreator(GitHubClient gitHubClient, String branchPrefix) {
        this.gitHubClient = gitHubClient;
        this.branchPrefix = branchPrefix;
    }

    public record BumpedDependency(String groupId, String artifactId, String oldVersion, String newVersion, String updatedBy) {}

    public GitHubClient.PrResult createUpdatePr(String owner, String repo, String pomPath, String targetBranch,
                                                  String updatedPomContent,
                                                  List<BumpedDependency> bumps, boolean dryRun) throws Exception {
        String branchName = "%s/%s/dep-updates".formatted(branchPrefix, targetBranch);

        String commitMessage = bumps.size() == 1
                ? "chore(deps): update %s:%s to %s".formatted(bumps.get(0).groupId(), bumps.get(0).artifactId(), bumps.get(0).newVersion())
                : "chore(deps): update %d dependencies".formatted(bumps.size());
        String prTitle = "chore(deps): automated dependency updates for %s".formatted(targetBranch);
        String prBody = buildPrBody(bumps);

        if (dryRun) {
            log.info("[DRY RUN] Would create/update PR in {}/{} to update: {}", owner, repo,
                    bumps.stream().map(b -> "%s:%s -> %s".formatted(b.groupId(), b.artifactId(), b.newVersion()))
                            .collect(Collectors.joining(", ")));
            return new GitHubClient.PrResult(0, "dry-run");
        }

        boolean branchAlreadyExists = gitHubClient.branchExists(owner, repo, branchName);
        GitHubClient.PrResult existingPr = branchAlreadyExists
                ? gitHubClient.findOpenPr(owner, repo, branchName)
                : null;

        if (!branchAlreadyExists) {
            String headSha = gitHubClient.getBranchSha(owner, repo, targetBranch);
            gitHubClient.createBranch(owner, repo, branchName, headSha);
        }

        GitHubClient.FileContent pomFile = gitHubClient.getFileContent(owner, repo, pomPath, branchName);

        if (updatedPomContent.equals(pomFile.content())) {
            log.info("No change needed for {}/{}", owner, repo);
            return null;
        }

        gitHubClient.updateFile(owner, repo, pomPath, updatedPomContent,
                pomFile.sha(), branchName, commitMessage);

        if (existingPr != null) {
            gitHubClient.updatePullRequest(owner, repo, existingPr.number(), prTitle, prBody);
            log.info("Updated PR #{}: {} ({})", existingPr.number(), existingPr.url(), owner + "/" + repo);
            return existingPr;
        } else {
            GitHubClient.PrResult result = gitHubClient.createPullRequest(owner, repo, prTitle, prBody, branchName, targetBranch);
            log.info("Created PR #{}: {} ({})", result.number(), result.url(), owner + "/" + repo);
            return result;
        }
    }

    public void directCommit(String owner, String repo, String pomPath, String targetBranch,
                             String updatedPomContent, List<BumpedDependency> bumps) throws Exception {
        GitHubClient.FileContent pomFile = gitHubClient.getFileContent(owner, repo, pomPath, targetBranch);

        if (updatedPomContent.equals(pomFile.content())) {
            log.info("No change needed for {}/{} ({})", owner, repo, targetBranch);
            return;
        }

        String bumpList = bumps.stream()
                .map(b -> "- %s:%s %s -> %s (by @%s)".formatted(
                        b.groupId(), b.artifactId(), b.oldVersion(), b.newVersion(),
                        b.updatedBy() != null ? b.updatedBy() : "unknown"))
                .collect(Collectors.joining("\n"));

        String mentions = bumps.stream()
                .map(BumpedDependency::updatedBy)
                .filter(u -> u != null && !"unknown".equals(u))
                .distinct()
                .map(u -> "@" + u.replaceFirst("^@", ""))
                .collect(Collectors.joining(" "));

        String commitMessage = "chore(deps): auto-update dependencies\n\n%s\n\ncc %s".formatted(bumpList, mentions);

        gitHubClient.updateFile(owner, repo, pomPath, updatedPomContent,
                pomFile.sha(), targetBranch, commitMessage);
        log.info("Auto-merged to {}/{} ({}) — notified: {}", owner, repo, targetBranch, mentions);
    }

    private String buildPrBody(List<BumpedDependency> bumps) {
        String bumpList = bumps.stream()
                .map(b -> "- `%s:%s` from `%s` to `%s` (deployed by @%s)".formatted(
                        b.groupId(), b.artifactId(), b.oldVersion(), b.newVersion(),
                        b.updatedBy() != null ? b.updatedBy() : "unknown"))
                .collect(Collectors.joining("\n"));
        return """
                ## Automated Dependency Update

                %s

                This PR was created automatically by the dependency-resolver.
                """.formatted(bumpList);
    }
}
