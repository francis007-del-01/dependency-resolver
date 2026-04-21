package com.depresolver.gate;

import com.depresolver.config.RepoConfig;
import com.depresolver.config.ServiceUserProperties;
import com.depresolver.github.GitHubClient;
import com.depresolver.github.GitHubClient.CommitAuthor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SnapshotGateServiceTest {

    @Mock private GitHubClient gitHubClient;

    private ServiceUserProperties serviceUsers;
    private SnapshotGateService gate;

    @BeforeEach
    void setUp() {
        serviceUsers = new ServiceUserProperties();
        serviceUsers.setNames(List.of("root"));
        serviceUsers.setEmails(List.of("Tech-t4i-svc-dbill-automation@intuit.com"));
        gate = new SnapshotGateService(gitHubClient, serviceUsers);
    }

    private RepoConfig repo() {
        RepoConfig r = new RepoConfig();
        r.setUrl("https://github.com/o/lib");
        r.setTriggerBranch("master");
        return r;
    }

    private CommitAuthor bot() {
        return new CommitAuthor("root", "Tech-t4i-svc-dbill-automation@intuit.com", "github-svc-sbseg-ci");
    }

    private CommitAuthor dev() {
        return new CommitAuthor("Jane Dev", "jane@example.com", "jane");
    }

    @Test void nonSnapshotReturnsUnchangedWithNoApiCalls() throws Exception {
        String result = gate.resolveEffectiveVersion(repo(), "1.2.0.0");
        assertEquals("1.2.0.0", result);
        verifyNoInteractions(gitHubClient);
    }

    @Test void nullVersionReturnsNull() {
        assertNull(gate.resolveEffectiveVersion(repo(), null));
        verifyNoInteractions(gitHubClient);
    }

    @Test void snapshotWithNoTagsPropagatesSnapshot() throws Exception {
        when(gitHubClient.listTags("o", "lib")).thenReturn(List.of("random", "release-1.0"));
        String result = gate.resolveEffectiveVersion(repo(), "1.2.1.0-SNAPSHOT");
        assertEquals("1.2.1.0-SNAPSHOT", result);
        verify(gitHubClient, never()).compareCommits(anyString(), anyString(), anyString(), anyString());
    }

    @Test void snapshotWithTagButOnlyBotCommitsReturnsReleaseVersion() throws Exception {
        when(gitHubClient.listTags("o", "lib")).thenReturn(List.of("v1.2.0.0"));
        when(gitHubClient.compareCommits(eq("o"), eq("lib"), eq("v1.2.0.0"), anyString()))
                .thenReturn(List.of(bot(), bot()));

        String result = gate.resolveEffectiveVersion(repo(), "1.2.1.0-SNAPSHOT");
        assertEquals("1.2.0.0", result);
    }

    @Test void snapshotWithRealCommitOnDevelopPropagatesSnapshot() throws Exception {
        when(gitHubClient.listTags("o", "lib")).thenReturn(List.of("v1.2.0.0"));
        when(gitHubClient.compareCommits("o", "lib", "v1.2.0.0", "master")).thenReturn(List.of(bot(), bot()));
        when(gitHubClient.compareCommits("o", "lib", "v1.2.0.0", "develop")).thenReturn(List.of(bot(), dev()));

        String result = gate.resolveEffectiveVersion(repo(), "1.2.1.0-SNAPSHOT");
        assertEquals("1.2.1.0-SNAPSHOT", result);
    }

    @Test void serviceUserEmailMatchIsCaseInsensitive() throws Exception {
        when(gitHubClient.listTags("o", "lib")).thenReturn(List.of("v1.2.0.0"));
        CommitAuthor upperEmail = new CommitAuthor("someone",
                "TECH-T4I-SVC-DBILL-AUTOMATION@INTUIT.COM", null);
        when(gitHubClient.compareCommits(eq("o"), eq("lib"), eq("v1.2.0.0"), anyString()))
                .thenReturn(List.of(upperEmail));

        String result = gate.resolveEffectiveVersion(repo(), "1.2.1.0-SNAPSHOT");
        assertEquals("1.2.0.0", result);
    }

    @Test void missingBranchIsSkippedNotFatal() throws Exception {
        when(gitHubClient.listTags("o", "lib")).thenReturn(List.of("v1.2.0.0"));
        when(gitHubClient.compareCommits("o", "lib", "v1.2.0.0", "master")).thenReturn(List.of(bot()));
        when(gitHubClient.compareCommits("o", "lib", "v1.2.0.0", "develop"))
                .thenThrow(new IOException("404 branch missing"));

        String result = gate.resolveEffectiveVersion(repo(), "1.2.1.0-SNAPSHOT");
        assertEquals("1.2.0.0", result);
    }

    @Test void listTagsFailurePropagatesSnapshot() throws Exception {
        when(gitHubClient.listTags("o", "lib")).thenThrow(new IOException("rate limited"));
        String result = gate.resolveEffectiveVersion(repo(), "1.2.1.0-SNAPSHOT");
        assertEquals("1.2.1.0-SNAPSHOT", result);
    }

    @Test void triggerBranchIsAlsoScannedWhenNotMasterOrDevelop() throws Exception {
        RepoConfig r = repo();
        r.setTriggerBranch("release/1.2");
        when(gitHubClient.listTags("o", "lib")).thenReturn(List.of("v1.2.0.0"));
        when(gitHubClient.compareCommits(eq("o"), eq("lib"), eq("v1.2.0.0"), anyString())).thenReturn(List.of(bot()));

        gate.resolveEffectiveVersion(r, "1.2.1.0-SNAPSHOT");

        verify(gitHubClient).compareCommits("o", "lib", "v1.2.0.0", "release/1.2");
        verify(gitHubClient).compareCommits("o", "lib", "v1.2.0.0", "master");
        verify(gitHubClient).compareCommits("o", "lib", "v1.2.0.0", "develop");
    }
}
