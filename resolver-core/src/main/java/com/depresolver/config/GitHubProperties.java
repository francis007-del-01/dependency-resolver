package com.depresolver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    private String token;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
