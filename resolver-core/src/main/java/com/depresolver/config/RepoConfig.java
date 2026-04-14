package com.depresolver.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RepoConfig {
    private String url;
    private String pomPath = "pom.xml";
    private String triggerBranch;
    private List<BranchConfig> targetBranches;

    public String getOwner() {
        // https://github.com/owner/repo → owner
        String[] parts = url.replaceFirst("https://github.com/", "").split("/");
        return parts[0];
    }

    public String getName() {
        // https://github.com/owner/repo → repo
        String[] parts = url.replaceFirst("https://github.com/", "").split("/");
        return parts[1].replaceFirst("\\.git$", "");
    }
}
