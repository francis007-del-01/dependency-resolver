package com.depresolver.config;

import com.depresolver.github.GitHubClient;
import com.depresolver.github.PullRequestCreator;
import com.depresolver.pom.PomModifier;
import com.depresolver.pom.PomParser;
import com.depresolver.registry.RegistryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Value("${github.token}")
    private String githubToken;

    @Value("${resolver.branch-prefix:deps}")
    private String branchPrefix;

    @Bean
    public GitHubClient gitHubClient() {
        return new GitHubClient(githubToken);
    }

    @Bean
    public RegistryClient registryClient(GitHubClient gitHubClient) {
        return new RegistryClient(gitHubClient);
    }

    @Bean
    public PullRequestCreator pullRequestCreator(GitHubClient gitHubClient) {
        return new PullRequestCreator(gitHubClient, branchPrefix);
    }

    @Bean
    public PomParser pomParser() {
        return new PomParser();
    }

    @Bean
    public PomModifier pomModifier() {
        return new PomModifier();
    }
}
