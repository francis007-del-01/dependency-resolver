package com.depresolver.version;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TagVersionSelector {

    private static final Pattern TAG_PATTERN = Pattern.compile("^v(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$");

    private TagVersionSelector() {}

    public static Optional<String> latestReleaseTag(List<String> tagNames) {
        if (tagNames == null) return Optional.empty();
        return tagNames.stream()
                .filter(name -> TAG_PATTERN.matcher(name).matches())
                .max(Comparator.comparing(TagVersionSelector::parts, TagVersionSelector::compareParts));
    }

    public static String stripLeadingV(String tagName) {
        return tagName.startsWith("v") ? tagName.substring(1) : tagName;
    }

    private static int[] parts(String tagName) {
        Matcher m = TAG_PATTERN.matcher(tagName);
        if (!m.matches()) throw new IllegalStateException("not a release tag: " + tagName);
        return new int[] {
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)),
        };
    }

    private static int compareParts(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return 0;
    }
}
