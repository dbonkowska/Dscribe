package io.github.dbonkowska.dscribe.llm;

import com.sun.net.httpserver.HttpServer;
import io.github.dbonkowska.dscribe.config.LlmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final String REPLY = """
            {"text":"hi","usage":{"seconds":9.2,"input_tokens":83,"output_tokens":30,"total_tokens":113,"cost":0.0005}}
            """;

    private HttpServer server;
    /** Written on the server thread, read on the test thread. */
    private volatile String path;
    private volatile String authorization;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Keeps what the client announced, in order — a lambda cannot, because usage is a default method. */
    private static final class Recording implements Transcript {
        final List<String> appended = new ArrayList<>();
        final List<String> usages = new ArrayList<>();

        @Override
        public void append(String request, String response) {
            appended.add(response);
        }

        @Override
        public void usage(String model, Usage usage) {
            usages.add(model + " " + usage);
        }
    }

    /**
     * The point here is the round trip — which path, which header, and what was recorded when — so
     * a real server answers, as in {@code HubClientTest}.
     */
    @Test
    void postsTheAudioAndReportsWhatItCostUnderTheModelItAskedFor() throws IOException {
        serve(200, REPLY);
        Recording recording = new Recording();

        Transcription heard = served("stt/m", recording).transcribe(new byte[] {1, 2, 3}, "mp3");

        assertEquals("hi", heard.text());
        assertEquals("/audio/transcriptions", path);
        assertEquals("Bearer k", authorization);
        assertEquals(List.of(REPLY), recording.appended);
        // the reply names no model, so the spend goes to the one that was asked
        assertEquals(List.of("stt/m " + new Usage(83, 30, 113, 0.0005)), recording.usages);
    }

    /** Recorded before the status check: a rejection is the exchange most worth keeping. */
    @Test
    void recordsARejectedCallBeforeThrowingWithItsBody() throws IOException {
        serve(500, "boom");
        Recording recording = new Recording();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> served("stt/m", recording).transcribe(new byte[] {1}, "mp3"));

        assertTrue(thrown.getMessage().contains("boom"), thrown::getMessage);
        assertEquals(List.of("boom"), recording.appended);
        assertEquals(List.of(), recording.usages, "a rejected call spent nothing");
    }

    @Test
    void refusesToTranscribeWhenNothingHasNamedAModel() throws IOException {
        serve(200, REPLY);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> served(null, Transcript.NONE).transcribe(new byte[] {1}, "mp3"));

        assertTrue(thrown.getMessage().contains("model"), thrown::getMessage);
        assertNull(path, "nothing may reach the provider without a model");
    }

    private LlmClient served(String model, Transcript transcript) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new LlmClient(new Config("k", null, baseUrl)).defaultModel(model).withTranscript(transcript);
    }

    private void serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path = exchange.getRequestURI().getPath();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getRequestBody().readAllBytes();
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream sink = exchange.getResponseBody()) {
                sink.write(out);
            }
        });
        server.start();
    }
}
