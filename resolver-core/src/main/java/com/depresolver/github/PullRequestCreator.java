package com.depresolver.github;

import com.depresolver.registry.ArtifactEntry;
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

    public record BumpedDependency(String groupId, String artifactId, String oldVersion, String newVersion) {}

    public GitHubClient.PrResult createUpdatePr(ArtifactEntry target, String targetBranch,
                                                  String updatedPomContent,
                                                  List<BumpedDependency> bumps, boolean dryRun) throws Exception {
        String owner = target.getRepoOwner();
        String repo = target.getRepoName();
        String pomPath = target.getPomPath();

        String branchSuffix = bumps.size() == 1
                ? "bump-%s-to-%s".formatted(bumps.get(0).artifactId(), bumps.get(0).newVersion())
                : "bump-deps-%d".formatted(Math.abs(bumps.hashCode()) % 100000);
        String branchName = "%s/%s/%s".formatted(branchPrefix, targetBranch, branchSuffix);

        // Idempotency: check if branch or PR already exists
        if (gitHubClient.branchExists(owner, repo, branchName)) {
            log.info("Branch {} already exists in {}/{}. Skipping.", branchName, owner, repo);
            return null;
        }

        if (gitHubClient.pullRequestExists(owner, repo, branchName)) {
            log.info("PR already exists for branch {} in {}/{}. Skipping.", branchName, owner, repo);
            return null;
        }

        // Get target branch HEAD SHA
        String headSha = gitHubClient.getBranchSha(owner, repo, targetBranch);

        // Fetch current pom.xml from the library repo to get its SHA for the commit
        GitHubClient.FileContent pomFile = gitHubClient.getFileContent(owner, repo, pomPath, targetBranch);

        if (updatedPomContent.equals(pomFile.content())) {
            log.info("No change needed for {}/{}", owner, repo);
            return null;
        }

        if (dryRun) {
            log.info("[DRY RUN] Would create PR in {}/{} to update: {}", owner, repo,
                    bumps.stream().map(b -> "%s:%s -> %s".formatted(b.groupId(), b.artifactId(), b.newVersion()))
                            .collect(Collectors.joining(", ")));
            return new GitHubClient.PrResult(0, "dry-run");
        }

        // Create branch
        gitHubClient.createBranch(owner, repo, branchName, headSha);

        // Commit updated pom.xml
        String commitMessage = bumps.size() == 1
                ? "chore(deps): update %s:%s to %s".formatted(bumps.get(0).groupId(), bumps.get(0).artifactId(), bumps.get(0).newVersion())
                : "chore(deps): update %d dependencies".formatted(bumps.size());
        gitHubClient.updateFile(owner, repo, pomPath, updatedPomContent,
                pomFile.sha(), branchName, commitMessage);

        // Create PR
        String prTitle = commitMessage;
        String bumpList = bumps.stream()
                .map(b -> "- `%s:%s` from `%s` to `%s`".formatted(b.groupId(), b.artifactId(), b.oldVersion(), b.newVersion()))
                .collect(Collectors.joining("\n"));
        String prBody = """
                ## Automated Dependency Update

                %s

                This PR was created automatically by the dependency-resolver.
                """.formatted(bumpList);

        GitHubClient.PrResult result = gitHubClient.createPullRequest(owner, repo, prTitle, prBody, branchName, targetBranch);
        log.info("PR created: {} ({})", result.url(), owner + "/" + repo);
        return result;
    }
}
