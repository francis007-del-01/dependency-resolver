package com.depresolver.config;

import com.depresolver.github.GitHubClient;
import com.depresolver.github.PullRequestCreator;
import com.depresolver.pom.PomManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;

@Configuration
public class AppConfig {

    @Value("${github.token}")
    private String githubToken;

    @Value("${resolver.branch-prefix:deps}")
    private String branchPrefix;

    @Value("${resolver.config-path:classpath:config.yaml}")
    private Resource configPath;

    @Bean
    public GitHubClient gitHubClient() {
        return new GitHubClient(githubToken);
    }

    @Bean
    public PullRequestCreator pullRequestCreator(GitHubClient gitHubClient) {
        return new PullRequestCreator(gitHubClient, branchPrefix);
    }

    @Bean
    public PomManager pomManager() {
        return new PomManager();
    }

    @Bean
    public ResolverConfig resolverConfig() throws IOException {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        return yamlMapper.readValue(configPath.getInputStream(), ResolverConfig.class);
    }
}
