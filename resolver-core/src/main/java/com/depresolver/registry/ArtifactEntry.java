package com.depresolver.registry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactEntry {
    private String groupId;
    private String artifactId;
    private String latestVersion;
    private String repoOwner;
    private String repoName;
    private List<String> targetBranches;
    private String pomPath;
    private String updatedAt;
}
