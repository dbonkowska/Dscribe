package io.github.dbonkowska.dscribe.llm;

import io.github.dbonkowska.dscribe.config.LlmConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Speech-to-text is a different endpoint from chat, with its own request and its own names for
 * usage. The usage names are the quiet part: read with chat's names they would parse as zero
 * tokens and still look like a figure.
 *
 * <p>Fixtures are invented; the names are not models that exist.
 */
class TranscriptionTest {

    private record Config(String apiKey, String model, String baseUrl) implements LlmConfig {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LlmClient pinned(String model) {
        return new LlmClient(new Config("k", null, "https://llm.test")).defaultModel(model);
    }

    @Test
    void sendsTheAudioAsBase64UnderInputAudio() {
        TranscriptionRequest request = pinned("stt/m").transcriptionRequest(new byte[] {1, 2, 3}, "mp3");

        assertEquals(
                MAPPER.readTree("{\"model\":\"stt/m\",\"input_audio\":{\"data\":\"AQID\",\"format\":\"mp3\"}}"),
                MAPPER.readTree(MAPPER.writeValueAsString(request)));
    }

    @Test
    void readsTheTextAndTheSpeechEndpointsOwnUsageNames() {
        Transcription parsed = LlmClient.parseTranscription("""
                {"text":"hi","usage":{"seconds":9.2,"input_tokens":83,"output_tokens":30,"total_tokens":113,"cost":0.0005}}
                """);

        assertEquals("hi", parsed.text());
        assertEquals(new Usage(83, 30, 113, 0.0005), parsed.usage());
    }

    /** Absent is not zero: a reply that reported nothing must not read as a free call. */
    @Test
    void leavesUsageAbsentWhenTheReplyCarriesNone() {
        assertNull(LlmClient.parseTranscription("{\"text\":\"hi\"}").usage());
    }

    /** The shape is from the docs until a real call confirms it, so extra fields must not break it. */
    @Test
    void ignoresFieldsItDoesNotKnow() {
        assertEquals("hi", LlmClient.parseTranscription("{\"text\":\"hi\",\"x\":1}").text());
    }
}
