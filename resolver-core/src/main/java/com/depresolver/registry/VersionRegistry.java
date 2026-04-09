package com.depresolver.registry;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class VersionRegistry {
    private List<ArtifactEntry> artifacts = new ArrayList<>();
}
