package com.depresolver.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionComparatorTest {

    @Test
    void olderMajorVersion() {
        assertTrue(VersionComparator.isOlderThan("1.0.0", "2.0.0"));
    }

    @Test
    void olderMinorVersion() {
        assertTrue(VersionComparator.isOlderThan("1.2.0", "1.3.0"));
    }

    @Test
    void olderPatchVersion() {
        assertTrue(VersionComparator.isOlderThan("1.2.3", "1.2.4"));
    }

    @Test
    void numericNotStringComparison() {
        assertTrue(VersionComparator.isOlderThan("1.9.0", "1.10.0"));
    }

    @Test
    void sameVersion() {
        assertFalse(VersionComparator.isOlderThan("1.0.0", "1.0.0"));
    }

    @Test
    void newerVersionNoDowngrade() {
        assertFalse(VersionComparator.isOlderThan("2.0.0", "1.5.0"));
    }

    @Test
    void snapshotOlderThanRelease() {
        assertTrue(VersionComparator.isOlderThan("1.0.0-SNAPSHOT", "1.0.0"));
    }

    @Test
    void releaseNotOlderThanSnapshot() {
        assertFalse(VersionComparator.isOlderThan("1.0.0", "1.0.0-SNAPSHOT"));
    }

    @Test
    void snapshotOlderThanNewerRelease() {
        assertTrue(VersionComparator.isOlderThan("1.0.0-SNAPSHOT", "2.0.0"));
    }

    @Test
    void nullVersions() {
        assertFalse(VersionComparator.isOlderThan(null, "1.0.0"));
        assertFalse(VersionComparator.isOlderThan("1.0.0", null));
        assertFalse(VersionComparator.isOlderThan(null, null));
    }

    @Test
    void differentLengthVersions() {
        assertTrue(VersionComparator.isOlderThan("1.0", "1.0.1"));
        assertFalse(VersionComparator.isOlderThan("1.0.1", "1.0"));
    }

    @Test
    void twoPartVersions() {
        assertTrue(VersionComparator.isOlderThan("1.5", "2.0"));
        assertFalse(VersionComparator.isOlderThan("2.0", "1.5"));
    }
}
