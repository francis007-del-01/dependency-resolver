package com.depresolver.scheduler;

import com.depresolver.config.BranchConfig;
import com.depresolver.config.RepoConfig;
import com.depresolver.config.ResolverConfig;
import com.depresolver.github.GitHubClient;
import com.depresolver.github.PullRequestCreator;
import com.depresolver.pom.PomModifier;
import com.depresolver.pom.PomParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResolverSchedulerTest {

    @Mock
    private GitHubClient gitHubClient;

    @Mock
    private PullRequestCreator prCreator;

    private PomParser pomParser;
    private PomModifier pomModifier;

    @BeforeEach
    void setUp() {
        pomParser = new PomParser();
        pomModifier = new PomModifier();
    }

    @Test
    void skipsReposWithNoTargetBranches() throws Exception {
        var repo = new RepoConfig();
        repo.setOwner("myorg");
        repo.setName("core-lib");
        repo.setTriggerBranch("master");
        // no targetBranches

        var config = new ResolverConfig();
        config.setRepos(List.of(repo));

        // Trigger branch pom
        when(gitHubClient.getFileContent("myorg", "core-lib", "pom.xml", "master"))
                .thenReturn(new GitHubClient.FileContent("<project><modelVersion>4.0.0</modelVersion><groupId>com.myorg</groupId><artifactId>core-lib</artifactId><version>2.0.0</version></project>", "sha1", "pom.xml"));
        when(gitHubClient.getLastCommitter("myorg", "core-lib", "master")).thenReturn("namin2");

        var scheduler = new ResolverScheduler(config, gitHubClient, pomParser, pomModifier, prCreator);
        scheduler.run();

        // No PR created since there are no target repos
        verifyNoInteractions(prCreator);
    }

    @Test
    void detectsOutdatedDepAndCallsPrCreator() throws Exception {
        // Trigger repo
        var triggerRepo = new RepoConfig();
        triggerRepo.setOwner("myorg");
        triggerRepo.setName("core-lib");
        triggerRepo.setTriggerBranch("master");

        // Target repo
        var branch = new BranchConfig();
        branch.setName("main");
        branch.setAutoMerge(false);

        var targetRepo = new RepoConfig();
        targetRepo.setOwner("myorg");
        targetRepo.setName("service-a");
        targetRepo.setTargetBranches(List.of(branch));

        var config = new ResolverConfig();
        config.setRepos(List.of(triggerRepo, targetRepo));

        // Trigger pom: core-lib 2.0.0
        when(gitHubClient.getFileContent("myorg", "core-lib", "pom.xml", "master"))
                .thenReturn(new GitHubClient.FileContent(
                        "<project><modelVersion>4.0.0</modelVersion><groupId>com.myorg</groupId><artifactId>core-lib</artifactId><version>2.0.0</version></project>",
                        "sha1", "pom.xml"));
        when(gitHubClient.getLastCommitter("myorg", "core-lib", "master")).thenReturn("namin2");

        // Target pom: depends on core-lib 1.0.0 (outdated)
        when(gitHubClient.getFileContent("myorg", "service-a", "pom.xml", "main"))
                .thenReturn(new GitHubClient.FileContent(
                        "<project><modelVersion>4.0.0</modelVersion><groupId>com.myorg</groupId><artifactId>service-a</artifactId><version>1.0.0</version><dependencies><dependency><groupId>com.myorg</groupId><artifactId>core-lib</artifactId><version>1.0.0</version></dependency></dependencies></project>",
                        "sha2", "pom.xml"));

        var scheduler = new ResolverScheduler(config, gitHubClient, pomParser, pomModifier, prCreator);
        scheduler.run();

        // PR should be created
        verify(prCreator).createUpdatePr(
                eq("myorg"), eq("service-a"), eq("pom.xml"), eq("main"),
                argThat(pom -> pom.contains("2.0.0") && !pom.contains("<version>1.0.0</version>")),
                argThat(bumps -> bumps.size() == 1
                        && bumps.get(0).groupId().equals("com.myorg")
                        && bumps.get(0).artifactId().equals("core-lib")
                        && bumps.get(0).oldVersion().equals("1.0.0")
                        && bumps.get(0).newVersion().equals("2.0.0")
                        && bumps.get(0).updatedBy().equals("namin2")),
                eq(false));
    }

    @Test
    void autoMergeCallsDirectCommit() throws Exception {
        var triggerRepo = new RepoConfig();
        triggerRepo.setOwner("myorg");
        triggerRepo.setName("core-lib");
        triggerRepo.setTriggerBranch("master");

        var branch = new BranchConfig();
        branch.setName("develop");
        branch.setAutoMerge(true);

        var targetRepo = new RepoConfig();
        targetRepo.setOwner("myorg");
        targetRepo.setName("service-a");
        targetRepo.setTargetBranches(List.of(branch));

        var config = new ResolverConfig();
        config.setRepos(List.of(triggerRepo, targetRepo));

        when(gitHubClient.getFileContent("myorg", "core-lib", "pom.xml", "master"))
                .thenReturn(new GitHubClient.FileContent(
                        "<project><modelVersion>4.0.0</modelVersion><groupId>com.myorg</groupId><artifactId>core-lib</artifactId><version>3.0.0</version></project>",
                        "sha1", "pom.xml"));
        when(gitHubClient.getLastCommitter("myorg", "core-lib", "master")).thenReturn("john");

        when(gitHubClient.getFileContent("myorg", "service-a", "pom.xml", "develop"))
                .thenReturn(new GitHubClient.FileContent(
                        "<project><modelVersion>4.0.0</modelVersion><groupId>com.myorg</groupId><artifactId>service-a</artifactId><version>1.0.0</version><dependencies><dependency><groupId>com.myorg</groupId><artifactId>core-lib</artifactId><version>1.5.0</version></dependency></dependencies></project>",
                        "sha2", "pom.xml"));

        var scheduler = new ResolverScheduler(config, gitHubClient, pomParser, pomModifier, prCreator);
        scheduler.run();

        // directCommit should be called, not createUpdatePr
        verify(prCreator).directCommit(
                eq("myorg"), eq("service-a"), eq("pom.xml"), eq("develop"),
                anyString(), anyList());
        verify(prCreator, never()).createUpdatePr(anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void skipsUpToDateDeps() throws Exception {
        var triggerRepo = new RepoConfig();
        triggerRepo.setOwner("myorg");
        triggerRepo.setName("core-lib");
        triggerRepo.setTriggerBranch("master");

        var branch = new BranchConfig();
        branch.setName("main");
        branch.setAutoMerge(false);

        var targetRepo = new RepoConfig();
        targetRepo.setOwner("myorg");
        targetRepo.setName("service-a");
        targetRepo.setTargetBranches(List.of(branch));

        var config = new ResolverConfig();
        config.setRepos(List.of(triggerRepo, targetRepo));

        // Both at 2.0.0 — already up to date
        when(gitHubClient.getFileContent("myorg", "core-lib", "pom.xml", "master"))
                .thenReturn(new GitHubClient.FileContent(
                        "<project><modelVersion>4.0.0</modelVersion><groupId>com.myorg</groupId><artifactId>core-lib</artifactId><version>2.0.0</version></project>",
                        "sha1", "pom.xml"));
        when(gitHubClient.getLastCommitter("myorg", "core-lib", "master")).thenReturn("namin2");

        when(gitHubClient.getFileContent("myorg", "service-a", "pom.xml", "main"))
                .thenReturn(new GitHubClient.FileContent(
                        "<project><modelVersion>4.0.0</modelVersion><groupId>com.myorg</groupId><artifactId>service-a</artifactId><version>1.0.0</version><dependencies><dependency><groupId>com.myorg</groupId><artifactId>core-lib</artifactId><version>2.0.0</version></dependency></dependencies></project>",
                        "sha2", "pom.xml"));

        var scheduler = new ResolverScheduler(config, gitHubClient, pomParser, pomModifier, prCreator);
        scheduler.run();

        verifyNoInteractions(prCreator);
    }

    @Test
    void noDowngrades() throws Exception {
        var triggerRepo = new RepoConfig();
        triggerRepo.setOwner("myorg");
        triggerRepo.setName("core-lib");
        triggerRepo.setTriggerBranch("master");

        var branch = new BranchConfig();
        branch.setName("main");
        branch.setAutoMerge(false);

        var targetRepo = new RepoConfig();
        targetRepo.setOwner("myorg");
        targetRepo.setName("service-a");
        targetRepo.setTargetBranches(List.of(branch));

        var config = new ResolverConfig();
        config.setRepos(List.of(triggerRepo, targetRepo));

        // Trigger has 1.0.0 but service has 2.0.0 — no downgrade
        when(gitHubClient.getFileContent("myorg", "core-lib", "pom.xml", "master"))
                .thenReturn(new GitHubClient.FileContent(
                        "<project><modelVersion>4.0.0</modelVersion><groupId>com.myorg</groupId><artifactId>core-lib</artifactId><version>1.0.0</version></project>",
                        "sha1", "pom.xml"));
        when(gitHubClient.getLastCommitter("myorg", "core-lib", "master")).thenReturn("namin2");

        when(gitHubClient.getFileContent("myorg", "service-a", "pom.xml", "main"))
                .thenReturn(new GitHubClient.FileContent(
                        "<project><modelVersion>4.0.0</modelVersion><groupId>com.myorg</groupId><artifactId>service-a</artifactId><version>1.0.0</version><dependencies><dependency><groupId>com.myorg</groupId><artifactId>core-lib</artifactId><version>2.0.0</version></dependency></dependencies></project>",
                        "sha2", "pom.xml"));

        var scheduler = new ResolverScheduler(config, gitHubClient, pomParser, pomModifier, prCreator);
        scheduler.run();

        verifyNoInteractions(prCreator);
    }
}
