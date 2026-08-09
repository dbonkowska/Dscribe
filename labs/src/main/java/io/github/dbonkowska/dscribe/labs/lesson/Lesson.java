package io.github.dbonkowska.dscribe.labs.lesson;

import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A lesson's inputs, kept outside the repository.
 *
 * <p>Prompts and task parameters describe the course exercise rather than the framework,
 * so they live wherever {@code labs.lessons.dir} points — alongside the course notes —
 * and never enter version control. Each lesson is a directory of plain files:
 *
 * <pre>
 * {lessons.dir}/s01e01/
 *     system.md         prompt template
 *     task.properties   parameters bound to a record
 * </pre>
 */
public record Lesson(Path dir) {

    private static final JavaPropsMapper PROPS = new JavaPropsMapper();

    public static Lesson of(LabsConfig config, String id) {
        Path dir = config.lessonsDir().resolve(id);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "No inputs for lesson '" + id + "' at " + dir
                            + " — check labs.lessons.dir in application.properties");
        }
        return new Lesson(dir);
    }

    /** Reads a prompt file as UTF-8. Pass the file name, e.g. {@code "system.md"}. */
    public String prompt(String fileName) {
        return read(dir.resolve(fileName));
    }

    /**
     * Binds {@code task.properties} to a record.
     *
     * <p>Read as UTF-8 explicitly — {@link java.util.Properties} would default to
     * ISO-8859-1 and mangle the diacritics these values contain.
     */
    public <T> T task(Class<T> type) {
        return PROPS.readValue(read(dir.resolve("task.properties")), type);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}