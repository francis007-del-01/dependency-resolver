package com.depresolver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "artifactory")
public class ArtifactoryProperties {

    private String baseUrl;
    private String releaseRepo;
    private String snapshotRepo;
    private String token;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getReleaseRepo() { return releaseRepo; }
    public void setReleaseRepo(String releaseRepo) { this.releaseRepo = releaseRepo; }

    public String getSnapshotRepo() { return snapshotRepo; }
    public void setSnapshotRepo(String snapshotRepo) { this.snapshotRepo = snapshotRepo; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
