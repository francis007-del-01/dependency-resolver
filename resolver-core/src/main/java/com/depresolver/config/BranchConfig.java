package com.depresolver.config;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BranchConfig {
    private String name;
    private boolean autoMerge;
}
