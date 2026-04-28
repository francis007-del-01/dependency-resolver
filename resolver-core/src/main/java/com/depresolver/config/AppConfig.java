package com.depresolver.config;

import com.depresolver.artifactory.ArtifactoryClient;
import com.depresolver.artifactory.MavenMetadataParser;
import com.depresolver.github.GitHubClient;
import com.depresolver.pom.PomManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        ArtifactoryProperties.class,
        GitHubProperties.class
})
public class AppConfig {

    @Bean
    public GitHubClient gitHubClient(GitHubProperties props) {
        return new GitHubClient(props.getToken());
    }

    @Bean
    public ArtifactoryClient artifactoryClient(ArtifactoryProperties props, PomManager pomManager, MavenMetadataParser metadataParser) {
        return new ArtifactoryClient(props.getBaseUrl(), props.getReleaseRepo(), props.getSnapshotRepo(), props.getToken(), pomManager, metadataParser);
    }

    @Bean
    public PomManager pomManager() {
        return new PomManager();
    }

    @Bean
    public MavenMetadataParser mavenMetadataParser() {
        return new MavenMetadataParser();
    }
}
