package com.depresolver.github;

import java.io.IOException;

public class GitHubConflictException extends IOException {
    public GitHubConflictException(String message) {
        super(message);
    }
}
