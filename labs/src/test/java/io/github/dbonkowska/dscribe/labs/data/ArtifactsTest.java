package io.github.dbonkowska.dscribe.labs.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void reportsWhetherAFileIsThereYet(@TempDir Path root) {
        Artifacts artifacts = Artifacts.of(root, "x01");

        assertFalse(artifacts.has("n.json"));
        artifacts.write("n.json", new Note("a"));
        assertTrue(artifacts.has("n.json"));
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