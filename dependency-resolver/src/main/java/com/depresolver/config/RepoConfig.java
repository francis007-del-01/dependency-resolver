package com.depresolver.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RepoConfig {
    private String owner;
    private String name;
    private String pomPath = "pom.xml";
    private String triggerBranch;
    private List<BranchConfig> targetBranches;
}
