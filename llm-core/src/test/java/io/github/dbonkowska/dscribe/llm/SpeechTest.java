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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
     * The point here is the round trip — which path, which header, what came back and what was
     * recorded — so a real server answers, as in {@code TranscriptionTest}.
     */
    @Test
    void returnsTheAudioAndRecordsItBySizeRatherThanAsBytes() throws IOException {
        serve(200, "audio/mpeg", "gen-1", new byte[] {1, 2, 3});
        Recording recording = new Recording();

        byte[] spoken = served("tts/m", recording).speak("Dzień dobry", "v1", null);

        assertArrayEquals(new byte[] {1, 2, 3}, spoken);
        assertEquals("/audio/speech", path);
        assertEquals("Bearer k", authorization);
        assertEquals(1, recording.appended.size());
        assertEquals(
                MAPPER.readTree("{\"audio\":\"<3 bytes audio/mpeg>\",\"generation\":\"gen-1\"}"),
                MAPPER.readTree(recording.appended.getFirst()));
    }

    /** The reply prices nothing, and saying so is not the same as saying it was free. */
    @Test
    void reportsTheCallAsUnpricedUnderTheModelItAskedFor() throws IOException {
        serve(200, "audio/mpeg", "gen-1", new byte[] {1});
        Recording recording = new Recording();

        served("tts/m", recording).speak("x", "v1", null);

        assertEquals(List.of("tts/m null"), recording.usages);
    }

    /** Recorded before the status check: a rejection is the exchange most worth keeping. */
    @Test
    void recordsARejectedCallBeforeThrowingWithItsBody() throws IOException {
        String body = "{\"error\":\"bad voice\"}";
        serve(400, "application/json", null, body.getBytes(StandardCharsets.UTF_8));
        Recording recording = new Recording();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> served("tts/m", recording).speak("x", "v1", null));

        assertTrue(thrown.getMessage().contains("bad voice"), thrown::getMessage);
        assertEquals(List.of(body), recording.appended);
        assertEquals(List.of(), recording.usages, "a rejected call spent nothing");
    }

    /**
     * A 200 that is not audio would be Base64-encoded and sent on as a recording, and the receiver
     * would then reject it for a reason that points nowhere near here.
     */
    @Test
    void refusesASuccessfulReplyThatIsNotAudio() throws IOException {
        serve(200, "application/json", null, "{\"error\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        Recording recording = new Recording();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> served("tts/m", recording).speak("x", "v1", null));

        assertTrue(thrown.getMessage().contains("application/json"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("{\"error\":\"x\"}"), thrown::getMessage);
        // kept as the text it is, not as a size: it is the reply worth reading
        assertEquals(List.of("{\"error\":\"x\"}"), recording.appended);
    }

    @Test
    void refusesAnEmptyRecording() throws IOException {
        serve(200, "audio/mpeg", null, new byte[0]);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> served("tts/m", new Recording()).speak("x", "v1", null));

        assertTrue(thrown.getMessage().contains("0 bytes"), thrown::getMessage);
    }

    @Test
    void refusesToSpeakWhenNothingHasNamedAModel() throws IOException {
        serve(200, "audio/mpeg", null, new byte[] {1});

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> served(null, Transcript.NONE).speak("x", "v1", null));

        assertTrue(thrown.getMessage().contains("model"), thrown::getMessage);
        assertNull(path, "nothing may reach the provider without a model");
    }

    private LlmClient served(String model, Transcript transcript) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new LlmClient(new Config("k", null, baseUrl)).defaultModel(model).withTranscript(transcript);
    }

    private void serve(int status, String contentType, String generation, byte[] body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path = exchange.getRequestURI().getPath();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            if (generation != null) {
                exchange.getResponseHeaders().set("X-Generation-Id", generation);
            }
            // -1 is how HttpServer is told the body is empty; 0 would mean chunked
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream sink = exchange.getResponseBody()) {
                sink.write(body);
            }
        });
        server.start();
    }
}
