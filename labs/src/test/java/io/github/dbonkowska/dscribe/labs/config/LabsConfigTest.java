package io.github.dbonkowska.dscribe.labs.config;

import io.github.dbonkowska.dscribe.config.LlmConfig;
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

    private static final String VISION_KEY = "openrouter.vision.model";

    @Test
    void resolvesEachModelSlotFromItsOwnKey() {
        // the whole point of the second slot: one override must not reach the other, or a flag
        // meant to try a different planner silently retargets the call that reads the image
        Properties props = new Properties();
        props.setProperty("openrouter.model", "main/m");
        props.setProperty(VISION_KEY, "vis/m");

        assertEquals("main/m", LabsConfig.resolveModel(props, "openrouter.model"));
        assertEquals("vis/m", LabsConfig.resolveModel(props, VISION_KEY));
    }

    @Test
    void namesNoVisionModelWhenTheKeyIsBlank() {
        Properties blank = new Properties();
        blank.setProperty(VISION_KEY, "");
        Properties whitespace = new Properties();
        whitespace.setProperty(VISION_KEY, "   ");

        assertNull(LabsConfig.resolveModel(blank, VISION_KEY));
        assertNull(LabsConfig.resolveModel(whitespace, VISION_KEY));
    }

    @Test
    void letsAFlagOverrideTheVisionModelInThePropertiesFile() {
        Properties props = new Properties();
        props.setProperty(VISION_KEY, "file/m");
        System.setProperty(VISION_KEY, "flag/m");
        try {
            assertEquals("flag/m", LabsConfig.resolveModel(props, VISION_KEY));
        } finally {
            System.clearProperty(VISION_KEY);
        }
        // the environment tier sits between these two and cannot be set from a test;
        // takesTheFirstOverrideThatIsActuallySet pins that ordering on its own
    }

    @Test
    void derivesTheEnvironmentVariableNameWithoutATurkishLocaleHazard() {
        // uppercasing under the platform default turns "vision" into "VİSİON" in a tr-TR locale,
        // and the variable would then never be found — silently, on someone else's machine
        assertEquals("OPENROUTER_VISION_MODEL", LabsConfig.environmentName(VISION_KEY));
        assertEquals("OPENROUTER_MODEL", LabsConfig.environmentName("openrouter.model"));
    }

    @Test
    void offersTheVisionSlotAsAConfigCarryingTheSameCredentials() {
        LlmConfig vision =
                new LabsConfig.Llm("k", "main/m", "vis/m", "https://llm.test").vision();

        assertEquals("vis/m", vision.model(), "only the model differs");
        assertEquals("k", vision.apiKey());
        assertEquals("https://llm.test", vision.baseUrl());
    }

    @Test
    void leavesTheVisionSlotUnnamedWhenNothingConfiguresIt() {
        // then the runner's own pin fills it, exactly as it does for the main slot
        assertNull(new LabsConfig.Llm("k", "main/m", null, "https://llm.test").vision().model());
    }
}
