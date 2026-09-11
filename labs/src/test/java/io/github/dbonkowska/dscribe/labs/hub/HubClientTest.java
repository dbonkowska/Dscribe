package io.github.dbonkowska.dscribe.labs.hub;

import com.sun.net.httpserver.HttpServer;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several things fail silently here. A file already on disk is never fetched again — every re-run
 * of a lesson leans on that, the download being the slow, rate-limited part, and a regression
 * shows up only as requests quietly costing money. The URL {@code fetchData} builds carries the
 * API key in its path, so anything that prints it leaks the key. And a response's headers are
 * where a rate limit announces itself: {@code verify} used to discard them, which made a limit
 * look like an unexplained rejection.
 *
 * <p>Two kinds of server here. The unroutable base URL is for the cases that must not reach the
 * network at all — the cached fetch, and the throw whose message is being inspected. The loopback
 * {@link HttpServer} is for the cases where the point *is* the round trip: a status and a header
 * only exist if something really sent them.
 */
class HubClientTest {

    private static final String UNREACHABLE = "http://127.0.0.1:1";
    private static final String KEY = "hub-key-fixture";
    private static final String TASK = "x-task";

    /** Invented, like every fixture here — it belongs to no lesson. */
    record Probe(String question) {}

    private HttpServer server;
    /** Written on the server thread, read on the test thread. */
    private volatile String received;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void servesAFileThatIsAlreadyOnDiskWithoutFetchingIt(@TempDir Path root) throws IOException {
        Path cached = Files.writeString(root.resolve("a.json"), "{}", StandardCharsets.UTF_8);

        assertEquals(cached, hub(root).fetch(UNREACHABLE + "/would-fail", cached));
    }

    @Test
    void buildsTheKeyedDataUrlItselfAndKeepsTheKeyOutOfWhatItThrows(@TempDir Path root) {
        // nothing at the destination, so the cache branch cannot answer for the URL this time.
        // Where the file lands is the caller's decision; the key-bearing URL is built in here.
        Path destination = root.resolve("nested").resolve("a.json");

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> hub(root).fetchData("a.json", destination));

        assertTrue(thrown.getMessage().contains("/data/***/a.json"), thrown::getMessage);
        assertFalse(thrown.getMessage().contains(KEY), thrown::getMessage);
    }

    /**
     * The reason this class changed at all. An attempt that failed is the one carrying the
     * instructions for when to come back, so it has to arrive intact rather than as an exception.
     */
    @Test
    void surfacesTheStatusHeadersAndBodyOfAFailedAttempt(@TempDir Path root) throws IOException {
        serve(503, Map.of("X-Test-Reset", "30"), "{\"code\":-1}");

        HubResponse response = served(root).send("probe", TASK, new Probe("q"));

        assertEquals(503, response.status());
        assertEquals("30", response.headers().firstValue("X-Test-Reset").orElse(null));
        assertEquals("{\"code\":-1}", response.body());
        assertTrue(response.isRetryable(), "503 is the exercise's own failure mode");
    }

    @Test
    void verifyStillReturnsTheBodyItAlwaysDid(@TempDir Path root) throws IOException {
        serve(200, Map.of(), "{\"ok\":true}");

        assertEquals("{\"ok\":true}", served(root).verify(TASK, new Probe("q")));
    }

    /**
     * Written before the status is looked at, so the attempt that failed is in the file even
     * though nothing above ever saw it as a return value.
     */
    @Test
    void recordsAFailedAttemptInTheTranscript(@TempDir Path root) throws IOException {
        serve(503, Map.of("X-Test-Reset", "30"), "{\"code\":-1}");
        RunTranscript transcript = transcript(root);

        hub(transcript).send("probe", TASK, new Probe("q"));

        String written = Files.readString(transcript.file(), StandardCharsets.UTF_8);
        assertTrue(written.contains("503"), () -> written);
        assertTrue(written.contains("{\"code\":-1}"), () -> written);
        // the JDK client normalises header names to lower case on the way in
        assertTrue(written.toLowerCase().contains("x-test-reset: 30"),
                "a rate limit announces itself in a header");
        assertFalse(written.contains(KEY), "the key must never reach disk");
    }

    @Test
    void mergesTheKeyAndTaskNameIntoWhatItSends(@TempDir Path root) throws IOException {
        serve(200, Map.of(), "{}");

        served(root).send("probe", TASK, new Probe("why"));

        assertTrue(received.contains("\"" + KEY + "\""), () -> received);
        assertTrue(received.contains("\"" + TASK + "\""), () -> received);
        assertTrue(received.contains("\"why\""), "the caller's answer travels under `answer`");
    }

    /**
     * The other half of the caching rule. {@code fetchData} exists to *not* fetch twice, which
     * every earlier lesson leans on; this lesson reads a file that changes underneath it, so the
     * two must not be allowed to drift into each other. A `downloadData` that ever answered from
     * memory would look correct on the first cycle of every run and be wrong on all the rest.
     */
    @Test
    void returnsTheDataFileContentRatherThanAPath(@TempDir Path root) throws IOException {
        serveData(200, "id,desc\n1,x");

        assertEquals("id,desc\n1,x", served(root).downloadData("a.csv"));
    }

    @Test
    void readsTheFileAgainEveryTimeRatherThanRememberingIt(@TempDir Path root) throws IOException {
        serveData(200, "first", "second");
        HubClient hub = served(root);

        assertEquals("first", hub.downloadData("a.csv"));
        assertEquals("second", hub.downloadData("a.csv"),
                "the input rotates; a cached read would never see the change");
    }

    /**
     * Weaker than it looks on a modern JDK, where the default charset is already UTF-8 — kept
     * because the principle is that the charset is *named*, and a future reader changing this
     * method has no other signal that the bytes are not ASCII.
     */
    @Test
    void decodesTheDataFileAsUtf8(@TempDir Path root) throws IOException {
        serveData(200, "id,desc\n1,zażółć gęślą jaźń");

        assertTrue(served(root).downloadData("a.csv").contains("zażółć gęślą jaźń"));
    }

    @Test
    void keepsTheKeyOutOfWhatItThrowsWhenTheDataFileIsMissing(@TempDir Path root) throws IOException {
        serveData(404, "no such file");

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> served(root).downloadData("a.csv"));

        assertTrue(thrown.getMessage().contains("/data/***/a.csv"), thrown::getMessage);
        assertFalse(thrown.getMessage().contains(KEY), thrown::getMessage);
    }

    /**
     * The content rotates, so a cycle that failed cannot be explained after the fact unless what
     * it read is in the file alongside what it sent.
     */
    @Test
    void recordsTheDownloadInTheTranscriptWithoutTheKey(@TempDir Path root) throws IOException {
        serveData(200, "id,desc\n1,x");
        RunTranscript transcript = transcript(root);

        hub(transcript).downloadData("a.csv");

        String written = Files.readString(transcript.file(), StandardCharsets.UTF_8);
        assertTrue(written.contains("1,x"), () -> written);
        assertTrue(written.contains("a.csv"), () -> written);
        assertFalse(written.contains(KEY), "the key must never reach disk");
    }

    /**
     * Serves the keyed data path, handing out each body in turn and repeating the last — so a
     * second read can legitimately differ from the first.
     */
    private void serveData(int status, String... bodies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/data/" + KEY + "/", exchange -> {
            byte[] out = bodies[Math.min(calls.getAndIncrement(), bodies.length - 1)]
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream sink = exchange.getResponseBody()) {
                sink.write(out);
            }
        });
        server.start();
    }

    /** Captures what arrived, so the request the caller never assembled by hand is assertable. */
    private void serve(int status, Map<String, String> headers, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verify", exchange -> {
            received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream sink = exchange.getResponseBody()) {
                sink.write(out);
            }
        });
        server.start();
    }

    private HubClient served(Path root) {
        return hub(transcript(root));
    }

    private HubClient hub(RunTranscript transcript) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HubClient(new LabsConfig.Hub(KEY, base, base + "/verify"), transcript);
    }

    private static HubClient hub(Path root) {
        return new HubClient(
                new LabsConfig.Hub(KEY, UNREACHABLE, UNREACHABLE + "/verify"), transcript(root));
    }

    private static RunTranscript transcript(Path root) {
        return RunTranscript.open(root.resolve("logs"), "x01", Map.of(), List.of(KEY));
    }
}
