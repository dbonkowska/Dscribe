package io.github.dbonkowska.dscribe.labs.hub;

import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A file already on disk is never fetched again. Every re-run of a lesson leans on that — the
 * download is the slow, rate-limited part — and it fails silently by simply working while
 * costing a request each time.
 *
 * <p>The URL below is unroutable on purpose: if the cache check ever regresses, this test fails
 * by trying to reach it rather than by quietly passing.
 */
class HubClientTest {

    private static final String UNREACHABLE = "http://127.0.0.1:1/would-fail";

    @Test
    void servesAFileThatIsAlreadyOnDiskWithoutFetchingIt(@TempDir Path root) throws IOException {
        Path cached = Files.writeString(root.resolve("a.json"), "{}", StandardCharsets.UTF_8);

        assertEquals(cached, hub(root).fetch(UNREACHABLE, cached));
    }

    @Test
    void writesLessonDataWhereverTheCallerAsksFor(@TempDir Path root) throws IOException {
        // the destination is the caller's to choose; only the hub URL, key included, stays here
        Path destination = Files.writeString(root.resolve("a.json"), "{}", StandardCharsets.UTF_8);

        assertEquals(destination, hub(root).fetchData("a.json", destination));
    }

    private static HubClient hub(Path root) {
        return new HubClient(
                new LabsConfig.Hub("k", "https://hub.test", "https://hub.test/verify"),
                RunTranscript.open(root.resolve("logs"), "x01", Map.of(), List.of("k")));
    }
}