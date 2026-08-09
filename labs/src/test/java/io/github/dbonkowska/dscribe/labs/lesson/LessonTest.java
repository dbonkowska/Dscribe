package io.github.dbonkowska.dscribe.labs.lesson;

import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static LabsConfig configPointingAt(Path lessonsDir) {
        return new LabsConfig(
                new LabsConfig.Llm("k", "m", "https://llm.test"),
                new LabsConfig.Hub("k", "https://hub.test", "https://hub.test/verify"),
                lessonsDir);
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
    void namesTheMissingDirectoryWhenTheLessonIsNotThere(@TempDir Path root) {
        LabsConfig config = configPointingAt(root);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> Lesson.of(config, "nope"));

        assertTrue(thrown.getMessage().contains("nope"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("labs.lessons.dir"), thrown.getMessage());
    }
}