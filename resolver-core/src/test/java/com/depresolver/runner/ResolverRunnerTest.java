package com.depresolver.runner;

import com.depresolver.artifactory.ArtifactoryClient;
import com.depresolver.github.GitHubClient;
import com.depresolver.pom.BumpedDependency;
import com.depresolver.pom.PomManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResolverRunnerTest {

    @Test
    void runFailsWhenReleaseGroupIdMissing() {
        GitHubClient gitHubClient = mock(GitHubClient.class);
        ArtifactoryClient artifactoryClient = mock(ArtifactoryClient.class);
        PomManager pomManager = mock(PomManager.class);
        ResolverRunner runner = new ResolverRunner(gitHubClient, artifactoryClient, pomManager);

        DefaultApplicationArguments args = new DefaultApplicationArguments(
                "--owner=my-org",
                "--repo=my-repo",
                "--branch=main");

        assertThrows(IllegalArgumentException.class, () -> runner.run(args));
    }

    @Test
    void runCreatesBranchAndPullRequestWhenBumpsExist() throws Exception {
        GitHubClient gitHubClient = mock(GitHubClient.class);
        ArtifactoryClient artifactoryClient = mock(ArtifactoryClient.class);
        PomManager pomManager = mock(PomManager.class);
        ResolverRunner runner = new ResolverRunner(gitHubClient, artifactoryClient, pomManager);

        DefaultApplicationArguments args = new DefaultApplicationArguments(
                "--owner=my-org",
                "--repo=my-repo",
                "--branch=main",
                "--pomPath=pom.xml",
                "--releaseGroupId=com.acme.libs");

        when(gitHubClient.getFileContent("my-org", "my-repo", "pom.xml", "main"))
                .thenReturn(new GitHubClient.FileContent("<project/>", "file-sha", "pom.xml"));
        when(pomManager.listCoordinatesForTargets("<project/>", List.of("com.acme.libs"), java.util.Set.of()))
                .thenReturn(List.of(new PomManager.PomCoordinates("com.acme.libs", "core-lib", "1.0.0")));
        when(artifactoryClient.latestReleaseVersion("com.acme.libs", "core-lib"))
                .thenReturn(Optional.of("1.1.0"));
        when(pomManager.findBumpsFromLatestVersions("<project/>", Map.of("com.acme.libs:core-lib", "1.1.0")))
                .thenReturn(List.of(new BumpedDependency("com.acme.libs", "core-lib", "1.0.0", "1.1.0")));
        when(pomManager.applyBumps("<project/>", List.of(new BumpedDependency("com.acme.libs", "core-lib", "1.0.0", "1.1.0"))))
                .thenReturn("<project><updated/></project>");
        when(gitHubClient.getBranchHeadSha("my-org", "my-repo", "main")).thenReturn("base-sha");
        when(gitHubClient.createPullRequest(eq("my-org"), eq("my-repo"), anyString(), anyString(), anyString(), eq("main")))
                .thenReturn(new GitHubClient.PullRequest(101, "https://github.com/my-org/my-repo/pull/101", "resolver/release-main-1", "main"));

        runner.run(args);

        verify(gitHubClient).createBranch(eq("my-org"), eq("my-repo"), anyString(), eq("base-sha"));
        verify(gitHubClient).updateFile(eq("my-org"), eq("my-repo"), eq("pom.xml"),
                eq("<project><updated/></project>"), eq("file-sha"), anyString(), anyString());
        verify(gitHubClient).createPullRequest(eq("my-org"), eq("my-repo"), anyString(), anyString(), anyString(), eq("main"));
    }

    @Test
    void runSkipsWriteOperationsWhenNoMatchingGroups() throws Exception {
        GitHubClient gitHubClient = mock(GitHubClient.class);
        ArtifactoryClient artifactoryClient = mock(ArtifactoryClient.class);
        PomManager pomManager = mock(PomManager.class);
        ResolverRunner runner = new ResolverRunner(gitHubClient, artifactoryClient, pomManager);

        DefaultApplicationArguments args = new DefaultApplicationArguments(
                "--owner=my-org",
                "--repo=my-repo",
                "--branch=main",
                "--releaseGroupId=com.missing");

        when(gitHubClient.getFileContent("my-org", "my-repo", "pom.xml", "main"))
                .thenReturn(new GitHubClient.FileContent("<project/>", "file-sha", "pom.xml"));
        when(pomManager.listCoordinatesForTargets("<project/>", List.of("com.missing"), java.util.Set.of()))
                .thenReturn(List.of());

        runner.run(args);

        verify(gitHubClient, never()).createBranch(anyString(), anyString(), anyString(), anyString());
        verify(gitHubClient, never()).updateFile(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(gitHubClient, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void runSupportsReleaseArtifactSelectorOnly() throws Exception {
        GitHubClient gitHubClient = mock(GitHubClient.class);
        ArtifactoryClient artifactoryClient = mock(ArtifactoryClient.class);
        PomManager pomManager = mock(PomManager.class);
        ResolverRunner runner = new ResolverRunner(gitHubClient, artifactoryClient, pomManager);

        DefaultApplicationArguments args = new DefaultApplicationArguments(
                "--owner=my-org",
                "--repo=my-repo",
                "--branch=main",
                "--releaseArtifact=com.intuit:pymt-lib");

        when(gitHubClient.getFileContent("my-org", "my-repo", "pom.xml", "main"))
                .thenReturn(new GitHubClient.FileContent("<project/>", "file-sha", "pom.xml"));
        when(pomManager.listCoordinatesForTargets("<project/>", List.of(), java.util.Set.of("com.intuit:pymt-lib")))
                .thenReturn(List.of(new PomManager.PomCoordinates("com.intuit", "pymt-lib", "1.0.0")));
        when(artifactoryClient.latestReleaseVersion("com.intuit", "pymt-lib"))
                .thenReturn(Optional.of("1.1.0"));
        when(pomManager.findBumpsFromLatestVersions("<project/>", Map.of("com.intuit:pymt-lib", "1.1.0")))
                .thenReturn(List.of(new BumpedDependency("com.intuit", "pymt-lib", "1.0.0", "1.1.0")));
        when(pomManager.applyBumps("<project/>", List.of(new BumpedDependency("com.intuit", "pymt-lib", "1.0.0", "1.1.0"))))
                .thenReturn("<project><updated/></project>");
        when(gitHubClient.getBranchHeadSha("my-org", "my-repo", "main")).thenReturn("base-sha");
        when(gitHubClient.createPullRequest(eq("my-org"), eq("my-repo"), anyString(), anyString(), anyString(), eq("main")))
                .thenReturn(new GitHubClient.PullRequest(99, "https://github.com/my-org/my-repo/pull/99", "resolver/release-main-1", "main"));

        runner.run(args);

        verify(pomManager).listCoordinatesForTargets("<project/>", List.of(), java.util.Set.of("com.intuit:pymt-lib"));
        verify(gitHubClient).createPullRequest(eq("my-org"), eq("my-repo"), anyString(), anyString(), anyString(), eq("main"));
    }

    @Test
    void runDropsArtifactSelectorsWhenGroupAlreadySelected() throws Exception {
        GitHubClient gitHubClient = mock(GitHubClient.class);
        ArtifactoryClient artifactoryClient = mock(ArtifactoryClient.class);
        PomManager pomManager = mock(PomManager.class);
        ResolverRunner runner = new ResolverRunner(gitHubClient, artifactoryClient, pomManager);

        DefaultApplicationArguments args = new DefaultApplicationArguments(
                "--owner=my-org",
                "--repo=my-repo",
                "--branch=main",
                "--releaseGroupId=com.intuit",
                "--releaseArtifact=com.intuit:pymt-lib");

        when(gitHubClient.getFileContent("my-org", "my-repo", "pom.xml", "main"))
                .thenReturn(new GitHubClient.FileContent("<project/>", "file-sha", "pom.xml"));
        when(pomManager.listCoordinatesForTargets("<project/>", List.of("com.intuit"), java.util.Set.of()))
                .thenReturn(List.of());

        runner.run(args);

        verify(pomManager).listCoordinatesForTargets("<project/>", List.of("com.intuit"), java.util.Set.of());
        verify(gitHubClient, never()).createBranch(anyString(), anyString(), anyString(), anyString());
        verify(gitHubClient, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
