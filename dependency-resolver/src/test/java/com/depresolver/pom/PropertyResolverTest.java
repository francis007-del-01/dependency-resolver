package com.depresolver.pom;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PropertyResolverTest {

    @Test
    void resolvesKnownProperty() {
        String result = PropertyResolver.resolve("${pool.version}", Map.of("pool.version", "1.0.0"));
        assertEquals("1.0.0", result);
    }

    @Test
    void returnsNullForMavenBuiltInProperty() {
        assertNull(PropertyResolver.resolve("${project.version}", Map.of()));
    }

    @Test
    void returnsNullForOtherBuiltInPrefixes() {
        assertNull(PropertyResolver.resolve("${settings.localRepository}", Map.of()));
        assertNull(PropertyResolver.resolve("${env.JAVA_HOME}", Map.of()));
        assertNull(PropertyResolver.resolve("${java.version}", Map.of()));
    }

    @Test
    void returnsNullForNestedPropertyReference() {
        String result = PropertyResolver.resolve("${nested}", Map.of("nested", "${other.prop}"));
        assertNull(result);
    }

    @Test
    void returnsNullForUndefinedCustomProperty() {
        assertNull(PropertyResolver.resolve("${undefined.prop}", Map.of("other", "1.0.0")));
    }

    @Test
    void returnsLiteralValueUnchanged() {
        assertEquals("1.0.0", PropertyResolver.resolve("1.0.0", Map.of()));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(PropertyResolver.resolve(null, Map.of()));
    }

    @Test
    void isPropertyReferenceDetectsExpressions() {
        assertTrue(PropertyResolver.isPropertyReference("${pool.version}"));
        assertTrue(PropertyResolver.isPropertyReference("${project.version}"));
        assertFalse(PropertyResolver.isPropertyReference("1.0.0"));
        assertFalse(PropertyResolver.isPropertyReference(null));
    }

    @Test
    void extractPropertyKeyReturnsKey() {
        assertEquals("pool.version", PropertyResolver.extractPropertyKey("${pool.version}"));
        assertEquals("project.version", PropertyResolver.extractPropertyKey("${project.version}"));
        assertNull(PropertyResolver.extractPropertyKey("1.0.0"));
        assertNull(PropertyResolver.extractPropertyKey(null));
    }
}
