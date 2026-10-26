package io.github.dbonkowska.dscribe.llm;

import io.github.dbonkowska.dscribe.config.LlmConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text-to-speech is a third endpoint, and the only one whose reply is not JSON. What it sends is
 * pinned here; the format is the part that would otherwise go wrong quietly, because the
 * provider's default is PCM and the receiver wants MP3.
 *
 * <p>Fixtures are invented; the names are not models or voices that exist.
 */
class SpeechTest {

    private record Config(String apiKey, String model, String baseUrl) implements LlmConfig {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LlmClient pinned(String model) {
        return new LlmClient(new Config("k", null, "https://llm.test")).defaultModel(model);
    }

    @Test
    void sendsTheTextTheVoiceAndTheInstructionsAndAlwaysAsksForMp3() {
        SpeechRequest request = pinned("tts/m").speechRequest("Dzień dobry", "v1", "calm");

        assertEquals(
                MAPPER.readTree("""
                        {"model":"tts/m","input":"Dzień dobry","voice":"v1","instructions":"calm","response_format":"mp3"}
                        """),
                MAPPER.readTree(MAPPER.writeValueAsString(request)));
    }

    /** Providers that do not take instructions ignore them at best, so none is sent when none is given. */
    @Test
    void leavesInstructionsOutWhenThereAreNone() {
        assertFalse(MAPPER.writeValueAsString(pinned("tts/m").speechRequest("x", "v1", null)).contains("instructions"));
    }

    @Test
    void treatsBlankInstructionsAsNone() {
        assertFalse(MAPPER.writeValueAsString(pinned("tts/m").speechRequest("x", "v1", "  ")).contains("instructions"));
    }

    @Test
    void refusesBlankText() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> pinned("tts/m").speechRequest(" ", "v1", null));

        assertTrue(thrown.getMessage().contains("input"), thrown::getMessage);
    }

    /** The provider refuses without one too, but with a message a long way from this call. */
    @Test
    void refusesABlankVoice() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> pinned("tts/m").speechRequest("x", "", null));

        assertTrue(thrown.getMessage().contains("voice"), thrown::getMessage);
    }
}
