package io.github.dbonkowska.dscribe.labs.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void readsPropertiesAsUtf8() throws IOException {
        // Properties#load(InputStream) decodes ISO-8859-1 whatever the file is. labs.lessons.dir
        // points outside the repository, so a diacritic in that path comes back mangled — and the
        // failure it produces blames labs.lessons.dir for a value that was correct all along.
        String text = "labs.lessons.dir=/notatki/ćwiczenia";

        Properties props =
                LabsConfig.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));

        assertEquals("/notatki/ćwiczenia", props.getProperty("labs.lessons.dir"));
    }

    @Test
    void takesTheFirstOverrideThatIsActuallySet() {
        // -D beats the environment beats the properties file: most specific to this run wins
        assertEquals("cli", LabsConfig.firstPresent("cli", "env", "file"));
        assertEquals("env", LabsConfig.firstPresent(null, "env", "file"));
        assertEquals("file", LabsConfig.firstPresent(null, null, "file"));
    }

    @Test
    void stepsPastABlankOverrideRatherThanHonouringIt() {
        // an empty -Dopenrouter.model= is a hand slipping, not a choice
        assertEquals("file", LabsConfig.firstPresent("", "   ", "file"));
    }

    @Test
    void namesNoModelWhenNothingSetsOne() {
        // the ordinary case. The runner's own pin fills the gap, and nothing here invents one:
        // a default model would let a typo'd override quietly run something nobody chose
        assertNull(LabsConfig.firstPresent(null, null, null));
    }
}
