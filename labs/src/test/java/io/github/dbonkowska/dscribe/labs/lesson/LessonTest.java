package io.github.dbonkowska.dscribe.labs.lesson;

import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader that keeps exercise content out of the repository. It fails silently in the
 * ways that matter — a mistyped directory yields a confusing NoSuchFile deep in a runner,
 * and the wrong charset corrupts values rather than throwing.
 *
 * <p>Fixtures here are invented, not drawn from any lesson.
 */
class LessonTest {

    record Params(String colour, int count, List<String> flavours) {}

    record Label(String text, String stance) {}

    private static LabsConfig configPointingAt(Path lessonsDir) {
        return new LabsConfig(
                new LabsConfig.Llm("k", "m", null, null, null, "https://llm.test"),
                new LabsConfig.Hub("k", "https://hub.test", "https://hub.test/verify"),
                lessonsDir,
                lessonsDir.resolve("data"));
    }

    private static Path lesson(Path root, String id) throws IOException {
        return Files.createDirectories(root.resolve(id));
    }

    @Test
    void bindsScalarsAndIndexedListsFromTaskProperties(@TempDir Path root) throws IOException {
        Files.writeString(lesson(root, "x01").resolve("task.properties"), """
                colour=green
                count=3
                flavours.1=salt
                flavours.2=pepper
                """, StandardCharsets.UTF_8);

        Params params = Lesson.of(configPointingAt(root), "x01").task(Params.class);

        assertEquals("green", params.colour());
        assertEquals(3, params.count());
        assertEquals(List.of("salt", "pepper"), params.flavours());
    }

    @Test
    void readsTaskPropertiesAsUtf8(@TempDir Path root) throws IOException {
        // java.util.Properties would decode this as ISO-8859-1 and mangle it
        Files.writeString(lesson(root, "x01").resolve("task.properties"), """
                colour=zażółć
                count=1
                flavours.1=gęślą
                """, StandardCharsets.UTF_8);

        Params params = Lesson.of(configPointingAt(root), "x01").task(Params.class);

        assertEquals("zażółć", params.colour());
        assertEquals(List.of("gęślą"), params.flavours());
    }

    @Test
    void readsAPromptAsUtf8(@TempDir Path root) throws IOException {
        Files.writeString(lesson(root, "x01").resolve("system.md"), "jaźń", StandardCharsets.UTF_8);

        assertEquals("jaźń", Lesson.of(configPointingAt(root), "x01").prompt("system.md"));
    }

    @Test
    void returnsARequiredPromptThatHasText(@TempDir Path root) throws IOException {
        Files.writeString(lesson(root, "x01").resolve("system.md"), "be brief", StandardCharsets.UTF_8);

        assertEquals("be brief", Lesson.of(configPointingAt(root), "x01").requiredPrompt("system.md"));
    }

    /**
     * A blank prompt would buy a run of a model that was told nothing, so it is refused before anything
     * is spent — and the refusal names the file and the folder, since a lesson can live in either kind.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "  \n\t"})
    void refusesARequiredPromptThatIsBlank(String content, @TempDir Path root) throws IOException {
        Files.writeString(lesson(root, "x01").resolve("system.md"), content, StandardCharsets.UTF_8);
        Lesson lesson = Lesson.of(configPointingAt(root), "x01");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> lesson.requiredPrompt("system.md"));

        assertTrue(thrown.getMessage().contains("system.md"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("x01"), thrown.getMessage());
    }

    /** The refusal is opt-in: a runner reading a file that may legitimately be empty still can. */
    @Test
    void stillReadsAnEmptyPromptWhenNotRequired(@TempDir Path root) throws IOException {
        Files.writeString(lesson(root, "x01").resolve("system.md"), "", StandardCharsets.UTF_8);

        assertEquals("", Lesson.of(configPointingAt(root), "x01").prompt("system.md"));
    }

    /**
     * A bundle file that is neither a prompt nor properties. Both elements and every component
     * have to bind — a component whose name misses leaves a null, and a label carrying a null
     * stance would compare unequal to everything and fail the gate it was written to guard.
     */
    @Test
    void bindsEveryRecordInAJsonList(@TempDir Path root) throws IOException {
        Files.writeString(lesson(root, "x01").resolve("eval.json"), """
                [{"text":"a","stance":"ok"},{"text":"b","stance":"problem"}]
                """, StandardCharsets.UTF_8);

        List<Label> labels = Lesson.of(configPointingAt(root), "x01").jsonList("eval.json", Label.class);

        assertEquals(List.of(new Label("a", "ok"), new Label("b", "problem")), labels);
    }

    @Test
    void readsAJsonListAsUtf8(@TempDir Path root) throws IOException {
        Files.writeString(lesson(root, "x01").resolve("eval.json"), """
                [{"text":"zażółć gęślą","stance":"ok"}]
                """, StandardCharsets.UTF_8);

        List<Label> labels = Lesson.of(configPointingAt(root), "x01").jsonList("eval.json", Label.class);

        assertEquals("zażółć gęślą", labels.getFirst().text());
    }

    @Test
    void namesTheFileWhenThereIsNoJsonListToRead(@TempDir Path root) throws IOException {
        lesson(root, "x01");
        Lesson lesson = Lesson.of(configPointingAt(root), "x01");

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> lesson.jsonList("eval.json", Label.class));

        assertTrue(thrown.getMessage().contains("eval.json"), thrown::getMessage);
    }

    /**
     * A lesson's bundle can share the issue folder the workflow creates for it,
     * {@code {N} - {id} {name}}, so the notes about a lesson and its inputs live in one place.
     */
    @Test
    void findsTheLessonInAnIssueFolderLedByItsId(@TempDir Path root) throws IOException {
        Path folder = Files.createDirectories(root.resolve("11 - x01 Some capability"));
        Files.writeString(folder.resolve("system.md"), "found", StandardCharsets.UTF_8);

        assertEquals("found", Lesson.of(configPointingAt(root), "x01").prompt("system.md"));
    }

    @Test
    void doesNotTakeALongerIdThatSharesThePrefix(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("3 - x011 Other capability"));
        LabsConfig config = configPointingAt(root);

        assertThrows(IllegalStateException.class, () -> Lesson.of(config, "x01"));
    }

    @Test
    void refusesToGuessWhenTwoFoldersClaimTheSameLesson(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("1 - x01 First"));
        Files.createDirectories(root.resolve("2 - x01 Second"));
        LabsConfig config = configPointingAt(root);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> Lesson.of(config, "x01"));

        assertTrue(thrown.getMessage().contains("1 - x01 First"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("2 - x01 Second"), thrown.getMessage());
    }

    @Test
    void namesTheMissingDirectoryWhenTheLessonIsNotThere(@TempDir Path root) {
        LabsConfig config = configPointingAt(root);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> Lesson.of(config, "nope"));

        assertTrue(thrown.getMessage().contains("nope"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("labs.lessons.dir"), thrown.getMessage());
    }
}