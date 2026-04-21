package com.depresolver.scheduler;

import com.depresolver.config.BranchConfig;
import com.depresolver.config.RepoConfig;
import com.depresolver.config.ResolverConfig;
import com.depresolver.config.ServiceUserProperties;
import com.depresolver.gate.SnapshotGateService;
import com.depresolver.github.GitHubClient;
import com.depresolver.github.PullRequestCreator;
import com.depresolver.pom.PomManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResolverSchedulerTest {

    @Mock private GitHubClient gitHubClient;
    @Mock private PullRequestCreator prCreator;

    private SnapshotGateService gate() {
        return new SnapshotGateService(gitHubClient, new ServiceUserProperties());
    }

    private GitHubClient.FileContent pom(String xml) { return new GitHubClient.FileContent(xml, "sha", "pom.xml"); }

    private RepoConfig trigger(String url, String branch) {
        var r = new RepoConfig(); r.setUrl(url); r.setTriggerBranch(branch); return r;
    }
    private RepoConfig target(String url, List<BranchConfig> branches) {
        var r = new RepoConfig(); r.setUrl(url); r.setTargetBranches(branches); return r;
    }
    private BranchConfig branch(String name, boolean autoMerge) {
        var b = new BranchConfig(); b.setName(name); b.setAutoMerge(autoMerge); return b;
    }

    @Test void detectsOutdatedAndCreatesPr() throws Exception {
        var config = new ResolverConfig();
        config.setRepos(List.of(
            trigger("https://github.com/o/lib", "master"),
            target("https://github.com/o/svc", List.of(branch("main", false)))
        ));
        when(gitHubClient.getFileContent("o", "lib", "pom.xml", "master")).thenReturn(pom("<project><modelVersion>4.0.0</modelVersion><groupId>com</groupId><artifactId>lib</artifactId><version>2.0.0</version></project>"));
        when(gitHubClient.getLastCommitter("o", "lib", "master")).thenReturn("dev1");
        when(gitHubClient.getFileContent("o", "svc", "pom.xml", "main")).thenReturn(pom("<project><modelVersion>4.0.0</modelVersion><groupId>com</groupId><artifactId>svc</artifactId><version>1.0.0</version><dependencies><dependency><groupId>com</groupId><artifactId>lib</artifactId><version>1.0.0</version></dependency></dependencies></project>"));

        new ResolverScheduler(config, gitHubClient, prCreator, new PomManager(), gate()).run();

        verify(prCreator).createUpdatePr(eq("o"), eq("svc"), eq("pom.xml"), eq("main"),
                argThat(p -> p.contains("2.0.0")),
                argThat(b -> b.size() == 1 && b.get(0).updatedBy().equals("dev1")), eq(false));
    }

    @Test void autoMergeCallsDirectCommit() throws Exception {
        var config = new ResolverConfig();
        config.setRepos(List.of(
            trigger("https://github.com/o/lib", "master"),
            target("https://github.com/o/svc", List.of(branch("develop", true)))
        ));
        when(gitHubClient.getFileContent("o", "lib", "pom.xml", "master")).thenReturn(pom("<project><modelVersion>4.0.0</modelVersion><groupId>com</groupId><artifactId>lib</artifactId><version>3.0.0</version></project>"));
        when(gitHubClient.getLastCommitter("o", "lib", "master")).thenReturn("dev2");
        when(gitHubClient.getFileContent("o", "svc", "pom.xml", "develop")).thenReturn(pom("<project><modelVersion>4.0.0</modelVersion><groupId>com</groupId><artifactId>svc</artifactId><version>1.0.0</version><dependencies><dependency><groupId>com</groupId><artifactId>lib</artifactId><version>1.0.0</version></dependency></dependencies></project>"));

        new ResolverScheduler(config, gitHubClient, prCreator, new PomManager(), gate()).run();

        verify(prCreator).directCommit(eq("o"), eq("svc"), eq("pom.xml"), eq("develop"), anyString(), anyList());
        verify(prCreator, never()).createUpdatePr(anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyBoolean());
    }

    @Test void skipsUpToDate() throws Exception {
        var config = new ResolverConfig();
        config.setRepos(List.of(
            trigger("https://github.com/o/lib", "master"),
            target("https://github.com/o/svc", List.of(branch("main", false)))
        ));
        when(gitHubClient.getFileContent("o", "lib", "pom.xml", "master")).thenReturn(pom("<project><modelVersion>4.0.0</modelVersion><groupId>com</groupId><artifactId>lib</artifactId><version>2.0.0</version></project>"));
        when(gitHubClient.getLastCommitter("o", "lib", "master")).thenReturn("dev1");
        when(gitHubClient.getFileContent("o", "svc", "pom.xml", "main")).thenReturn(pom("<project><modelVersion>4.0.0</modelVersion><groupId>com</groupId><artifactId>svc</artifactId><version>1.0.0</version><dependencies><dependency><groupId>com</groupId><artifactId>lib</artifactId><version>2.0.0</version></dependency></dependencies></project>"));

        new ResolverScheduler(config, gitHubClient, prCreator, new PomManager(), gate()).run();

        verifyNoInteractions(prCreator);
    }

    @Test void noDowngrade() throws Exception {
        var config = new ResolverConfig();
        config.setRepos(List.of(
            trigger("https://github.com/o/lib", "master"),
            target("https://github.com/o/svc", List.of(branch("main", false)))
        ));
        when(gitHubClient.getFileContent("o", "lib", "pom.xml", "master")).thenReturn(pom("<project><modelVersion>4.0.0</modelVersion><groupId>com</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>"));
        when(gitHubClient.getLastCommitter("o", "lib", "master")).thenReturn("dev1");
        when(gitHubClient.getFileContent("o", "svc", "pom.xml", "main")).thenReturn(pom("<project><modelVersion>4.0.0</modelVersion><groupId>com</groupId><artifactId>svc</artifactId><version>1.0.0</version><dependencies><dependency><groupId>com</groupId><artifactId>lib</artifactId><version>2.0.0</version></dependency></dependencies></project>"));

        new ResolverScheduler(config, gitHubClient, prCreator, new PomManager(), gate()).run();

        verifyNoInteractions(prCreator);
    }

    @Test void noTargetBranchesSkipped() throws Exception {
        var config = new ResolverConfig();
        config.setRepos(List.of(trigger("https://github.com/o/lib", "master")));
        when(gitHubClient.getFileContent("o", "lib", "pom.xml", "master")).thenReturn(pom("<project><modelVersion>4.0.0</modelVersion><groupId>com</groupId><artifactId>lib</artifactId><version>2.0.0</version></project>"));
        when(gitHubClient.getLastCommitter("o", "lib", "master")).thenReturn("dev1");

        new ResolverScheduler(config, gitHubClient, prCreator, new PomManager(), gate()).run();

        verifyNoInteractions(prCreator);
    }
}
