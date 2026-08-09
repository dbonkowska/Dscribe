package io.github.dbonkowska.dscribe.labs.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabsConfigTest {

    @Test
    void fallsBackWhenKeyIsAbsent() {
        assertEquals("fallback", LabsConfig.orDefault(new Properties(), "missing", "fallback"));
    }

    @Test
    void usesTheConfiguredValueWhenPresent() {
        Properties props = new Properties();
        props.setProperty("hub.base.url", "https://example.test");

        assertEquals("https://example.test", LabsConfig.orDefault(props, "hub.base.url", "fallback"));
    }

    @Test
    void treatsAnEmptyValueAsAbsent() {
        // this is the whole reason orDefault exists: Properties#getProperty(key, default)
        // returns "" here, which would hand HubClient an empty URL
        Properties props = new Properties();
        props.setProperty("hub.base.url", "");

        assertEquals("fallback", LabsConfig.orDefault(props, "hub.base.url", "fallback"));
    }

    @Test
    void treatsAWhitespaceOnlyValueAsAbsent() {
        Properties props = new Properties();
        props.setProperty("hub.base.url", "   ");

        assertEquals("fallback", LabsConfig.orDefault(props, "hub.base.url", "fallback"));
    }
}