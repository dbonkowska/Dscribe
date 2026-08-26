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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two things fail silently here. A file already on disk is never fetched again — every re-run of
 * a lesson leans on that, the download being the slow, rate-limited part, and a regression shows
 * up only as requests quietly costing money. And the URL {@code fetchData} builds carries the API
 * key in its path, so anything that prints it leaks the key.
 *
 * <p>The base URL is unroutable on purpose: a test that reached the network would pass or fail on
 * the hub's mood. The cached case fails by trying to reach it; the uncached case is meant to.
 */
class HubClientTest {

    private static final String UNREACHABLE = "http://127.0.0.1:1";
    private static final String KEY = "hub-key-fixture";

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

    private static HubClient hub(Path root) {
        return new HubClient(
                new LabsConfig.Hub(KEY, UNREACHABLE, UNREACHABLE + "/verify"),
                RunTranscript.open(root.resolve("logs"), "x01", Map.of(), List.of(KEY)));
    }
}
