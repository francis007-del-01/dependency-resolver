package com.depresolver.version;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TagVersionSelectorTest {

    @Test void picksHighestSemverFromMixedTags() {
        Optional<String> tag = TagVersionSelector.latestReleaseTag(List.of(
                "v1.2.0.0", "release-candidate", "v1.2.9.0", "v1.2.10.0", "v0.9.0.0", "random"
        ));
        assertEquals(Optional.of("v1.2.10.0"), tag);
    }

    @Test void numericNotLexicalComparison() {
        Optional<String> tag = TagVersionSelector.latestReleaseTag(List.of("v1.2.9.0", "v1.2.10.0"));
        assertEquals(Optional.of("v1.2.10.0"), tag);
    }

    @Test void majorBeatsMinor() {
        Optional<String> tag = TagVersionSelector.latestReleaseTag(List.of("v1.99.0.0", "v2.0.0.0"));
        assertEquals(Optional.of("v2.0.0.0"), tag);
    }

    @Test void emptyListYieldsEmpty() {
        assertEquals(Optional.empty(), TagVersionSelector.latestReleaseTag(List.of()));
    }

    @Test void nullListYieldsEmpty() {
        assertEquals(Optional.empty(), TagVersionSelector.latestReleaseTag(null));
    }

    @Test void nonMatchingTagsIgnored() {
        Optional<String> tag = TagVersionSelector.latestReleaseTag(List.of("v1.2.3", "1.2.3.4", "release/v1.0.0.0"));
        assertEquals(Optional.empty(), tag);
    }

    @Test void stripLeadingVReturnsBareVersion() {
        assertEquals("1.2.0.0", TagVersionSelector.stripLeadingV("v1.2.0.0"));
        assertEquals("1.2.0.0", TagVersionSelector.stripLeadingV("1.2.0.0"));
    }
}
