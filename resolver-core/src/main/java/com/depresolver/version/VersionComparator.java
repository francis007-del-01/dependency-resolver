package com.depresolver.version;

public class VersionComparator {

    /**
     * Returns true if currentVersion is older than latestVersion.
     * Supports Maven-style versions like 1.0.0, 1.0.0-SNAPSHOT, 2.1.3, etc.
     */
    public static boolean isOlderThan(String currentVersion, String latestVersion) {
        if (currentVersion == null || latestVersion == null) {
            return false;
        }
        if (currentVersion.equals(latestVersion)) {
            return false;
        }

        String[] currentParts = normalize(currentVersion);
        String[] latestParts = normalize(latestVersion);

        int length = Math.max(currentParts.length, latestParts.length);
        for (int i = 0; i < length; i++) {
            int c = i < currentParts.length ? parseNum(currentParts[i]) : 0;
            int l = i < latestParts.length ? parseNum(latestParts[i]) : 0;
            if (c < l) return true;
            if (c > l) return false;
        }

        // Numeric parts are equal — SNAPSHOT is older than release
        boolean currentIsSnapshot = currentVersion.contains("-SNAPSHOT");
        boolean latestIsSnapshot = latestVersion.contains("-SNAPSHOT");
        if (currentIsSnapshot && !latestIsSnapshot) return true;

        return false;
    }

    private static String[] normalize(String version) {
        // Strip qualifiers like -SNAPSHOT, -RC1, -beta, etc.
        String base = version.split("-")[0];
        return base.split("\\.");
    }

    private static int parseNum(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
