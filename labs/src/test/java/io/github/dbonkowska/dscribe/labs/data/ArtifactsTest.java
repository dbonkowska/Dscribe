package io.github.dbonkowska.dscribe.labs.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every lesson's files pass through here, and the failures are the quiet kind: a write that
 * lands somewhere other than the lesson's own directory, or a round trip that goes through the
 * platform default charset and comes back subtly wrong on one machine and not another.
 *
 * <p>The fixture is invented, and one value carries diacritics so the charset has teeth.
 */
class ArtifactsTest {

    record Note(String text) {}

    private static final String DIACRITICS = "zażółć";

    @Test
    void addressesFilesInsideTheEpisodesOwnDirectory(@TempDir Path root) {
        assertEquals(root.resolve("x01").resolve("a.json"), Artifacts.of(root, "x01").file("a.json"));
    }

    @Test
    void roundTripsThroughUtf8RegardlessOfThePlatformDefault(@TempDir Path root) throws IOException {
        Artifacts artifacts = Artifacts.of(root, "x01");

        artifacts.write("n.json", new Note(DIACRITICS));

        assertEquals(DIACRITICS, artifacts.read("n.json", Note.class).text());

        // the bytes on disk have to be UTF-8 too, not just self-consistent within one mapper
        String raw = Files.readString(artifacts.file("n.json"), StandardCharsets.UTF_8);
        assertTrue(raw.contains(DIACRITICS), () -> "expected UTF-8 bytes on disk, got: " + raw);
    }

    @Test
    void createsTheEpisodeDirectoryOnFirstWrite(@TempDir Path root) {
        Artifacts artifacts = Artifacts.of(root, "x01");
        assertFalse(Files.exists(root.resolve("x01")));

        artifacts.write("n.json", new Note("a"));

        assertTrue(Files.exists(artifacts.file("n.json")));
    }

    @Test
    void readsAListBackInOrder(@TempDir Path root) {
        Artifacts artifacts = Artifacts.of(root, "x01");
        List<Note> written = List.of(new Note("a"), new Note("b"), new Note("c"));

        artifacts.write("l.json", written);

        assertEquals(written, artifacts.readList("l.json", Note.class));
    }

    @Test
    void namesTheFullPathWhenThereIsNothingToRead(@TempDir Path root) {
        Artifacts artifacts = Artifacts.of(root, "x01");

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> artifacts.read("missing.json", Note.class));

        assertTrue(
                thrown.getMessage().contains(artifacts.file("missing.json").toString()),
                thrown::getMessage);
    }

    @Test
    void unpacksEveryEntryUnderTheEpisodesOwnDirectory(@TempDir Path root) throws IOException {
        Artifacts artifacts = Artifacts.of(root, "x01");
        zip(artifacts.file("bundle.zip"), entries("a.json", "{\"n\":1}", "sub/b.json", "{\"n\":2}"));

        artifacts.unzip("bundle.zip", "bundle");

        assertEquals("{\"n\":1}", text(artifacts.file("bundle/a.json")));
        assertEquals("{\"n\":2}", text(artifacts.file("bundle/sub/b.json")));
    }

    /**
     * Fetch once, unpack once. A second run must neither pay for the work again nor quietly
     * overwrite what an earlier one left behind.
     */
    @Test
    void leavesAnAlreadyUnpackedDirectoryAlone(@TempDir Path root) throws IOException {
        Artifacts artifacts = Artifacts.of(root, "x01");
        zip(artifacts.file("bundle.zip"), entries("a.json", "fresh"));
        artifacts.unzip("bundle.zip", "bundle");
        Files.writeString(artifacts.file("bundle/a.json"), "sentinel", StandardCharsets.UTF_8);

        artifacts.unzip("bundle.zip", "bundle");

        assertEquals("sentinel", text(artifacts.file("bundle/a.json")));
    }

    /**
     * An archive is not a trusted input just because we downloaded it. An entry named its way out
     * of the target directory writes wherever it likes, and nothing downstream would notice.
     *
     * <p>The escaping entry sits <em>second</em> on purpose: a guard that inspects only the first
     * entry passes this fixture's mirror image and guards nothing. Nothing at all is written —
     * the whole archive is refused, not the one entry, so a partial extraction cannot be mistaken
     * for a finished one by the skip above.
     */
    @Test
    void refusesAnEntryThatWouldLandOutsideTheTargetDirectory(@TempDir Path root) throws IOException {
        Artifacts artifacts = Artifacts.of(root, "x01");
        zip(artifacts.file("bundle.zip"), entries("ok.json", "fine", "../escape.txt", "owned"));

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> artifacts.unzip("bundle.zip", "bundle"));

        assertTrue(thrown.getMessage().contains("../escape.txt"), thrown::getMessage);
        assertFalse(Files.exists(artifacts.file("escape.txt")), "nothing may land outside");
        assertFalse(Files.exists(artifacts.file("bundle/ok.json")), "nor may the entries before it");
    }

    /**
     * The other escape shape, and the one any check on the name itself misses. This entry
     * traverses nowhere — it discards the target. Resolving {@code /escape.txt} against
     * {@code C:\...\x01\bundle} yields {@code C:\escape.txt}, and notably {@code isAbsolute()} is
     * <em>false</em> for it on Windows, so a guard written around that flag would wave it
     * through. Comparing the resolved path against the target is what catches all three shapes:
     * a walk-up, a rooted name, and a fully qualified one.
     *
     * <p>Nothing is asserted about the world outside the temporary directory, deliberately: a
     * guard that let this through would be writing to the drive root, and a test should not need
     * that to have happened in order to pass.
     */
    @Test
    void refusesAnEntryNamedAsARootedPath(@TempDir Path root) throws IOException {
        Artifacts artifacts = Artifacts.of(root, "x01");
        zip(artifacts.file("bundle.zip"), entries("ok.json", "fine", "/escape.txt", "owned"));

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> artifacts.unzip("bundle.zip", "bundle"));

        assertTrue(thrown.getMessage().contains("/escape.txt"), thrown::getMessage);
        assertFalse(Files.exists(artifacts.file("bundle/ok.json")), "the whole archive is refused");
    }

    private static String text(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Insertion-ordered, because which entry comes second is the point of one test above. */
    private static Map<String, String> entries(String... nameThenContent) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < nameThenContent.length; i += 2) {
            entries.put(nameThenContent[i], nameThenContent[i + 1]);
        }
        return entries;
    }

    private static void zip(Path target, Map<String, String> entries) throws IOException {
        Files.createDirectories(target.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    @Test
    void ignoresFieldsTheReadingLessonDoesNotDeclare(@TempDir Path root) throws IOException {
        // one lesson writes a rich record; the next reads only the fields it needs
        Artifacts artifacts = Artifacts.of(root, "x01");
        Files.createDirectories(artifacts.file("n.json").getParent());
        Files.writeString(
                artifacts.file("n.json"),
                "{\"text\":\"a\",\"extra\":42}",
                StandardCharsets.UTF_8);

        assertEquals("a", artifacts.read("n.json", Note.class).text());
    }
}