package io.github.dbonkowska.dscribe.labs.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabsConfigTest {

    private static final Path DEFAULT_DATA_DIR = Path.of("labs", "data").toAbsolutePath().normalize();

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

    @Test
    void defaultsTheDataDirWhenTheKeyIsAbsent() {
        assertEquals(DEFAULT_DATA_DIR, LabsConfig.dataDir(new Properties()));
    }

    @Test
    void treatsABlankOrWhitespaceOnlyDataDirAsAbsent() {
        Properties blank = new Properties();
        blank.setProperty("labs.data.dir", "");
        Properties whitespace = new Properties();
        whitespace.setProperty("labs.data.dir", "   ");

        assertEquals(DEFAULT_DATA_DIR, LabsConfig.dataDir(blank));
        assertEquals(DEFAULT_DATA_DIR, LabsConfig.dataDir(whitespace));
    }

    @Test
    void absolutisesAndNormalisesAConfiguredDataDir() {
        // a relative path only resolved when the process was launched from the repository root
        Properties props = new Properties();
        props.setProperty("labs.data.dir", "target/./scratch");

        assertEquals(Path.of("target", "scratch").toAbsolutePath(), LabsConfig.dataDir(props));
    }
}