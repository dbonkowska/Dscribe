package io.github.dbonkowska.dscribe.labs.lesson;

import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A lesson's inputs, kept outside the repository.
 *
 * <p>Prompts and task parameters describe the course exercise rather than the framework,
 * so they live wherever {@code labs.lessons.dir} points — alongside the course notes —
 * and never enter version control. Each lesson is a directory of plain files:
 *
 * <pre>
 * {lessons.dir}/s01e01/
 *     system.md         system prompt
 *     user.md           user prompt, where the lesson has one
 *     task.properties   parameters bound to a record
 * </pre>
 */
public record Lesson(Path dir) {

    private static final JavaPropsMapper PROPS = new JavaPropsMapper();

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

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

    /**
     * Reads a bundle file that is a JSON array of records. Pass the file name, e.g.
     * {@code "eval.json"}.
     *
     * <p>A third kind of bundle file, beside the prompts and {@code task.properties}. It exists
     * because a hand-labelled sample is a list of pairs whose left-hand side is free text —
     * properties keys cannot carry that, and a prompt has no structure to bind.
     *
     * <p>Unknown fields are ignored, as in {@code Artifacts}: a file may carry a note to whoever
     * maintains it that the code has no interest in.
     */
    public <T> List<T> jsonList(String fileName, Class<T> element) {
        return JSON.readerForListOf(element).readValue(read(dir.resolve(fileName)));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}