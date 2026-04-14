package com.depresolver.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ResolverConfig {
    private List<RepoConfig> repos;
}
