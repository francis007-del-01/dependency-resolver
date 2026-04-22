package com.depresolver.config;

import com.depresolver.artifactory.ArtifactoryClient;
import com.depresolver.artifactory.ArtifactoryProperties;
import com.depresolver.github.GitHubClient;
import com.depresolver.pom.PomManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Value("${github.token}")
    private String githubToken;

    @Bean
    public GitHubClient gitHubClient() {
        return new GitHubClient(githubToken);
    }

    @Bean
    public ArtifactoryClient artifactoryClient(ArtifactoryProperties props) {
        return new ArtifactoryClient(props.getBaseUrl(), props.getReleaseRepo(), props.getSnapshotRepo(), props.getToken());
    }

    @Bean
    public PomManager pomManager() {
        return new PomManager();
    }
}
