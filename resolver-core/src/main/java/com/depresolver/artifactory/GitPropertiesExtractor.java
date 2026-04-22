package com.depresolver.artifactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GitPropertiesExtractor {

    public record GitInfo(String commitSha, boolean dirty) {}

    private GitPropertiesExtractor() {}

    public static Optional<GitInfo> extract(byte[] jarBytes) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.equals("git.properties") && !name.equals("META-INF/git.properties")) {
                    continue;
                }
                Properties props = new Properties();
                props.load(zis);
                String sha = props.getProperty("git.commit.id");
                if (sha == null || sha.isBlank()) return Optional.empty();
                boolean dirty = Boolean.parseBoolean(props.getProperty("git.dirty", "false"));
                return Optional.of(new GitInfo(sha, dirty));
            }
        }
        return Optional.empty();
    }
}
