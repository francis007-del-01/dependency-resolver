package com.depresolver.pom;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PropertyResolver {

    private static final Pattern PROPERTY_PATTERN = Pattern.compile("\\$\\{(.+?)}");

    public static String resolve(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        Matcher matcher = PROPERTY_PATTERN.matcher(value);
        if (matcher.matches()) {
            String key = matcher.group(1);
            String resolved = properties.get(key);
            return resolved != null ? resolved : value;
        }
        return value;
    }

    public static boolean isPropertyReference(String value) {
        return value != null && PROPERTY_PATTERN.matcher(value).matches();
    }

    public static String extractPropertyKey(String value) {
        if (value == null) return null;
        Matcher matcher = PROPERTY_PATTERN.matcher(value);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }
}
