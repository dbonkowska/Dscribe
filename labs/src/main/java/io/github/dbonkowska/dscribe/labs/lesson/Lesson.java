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
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 *
 * <p>The directory may instead be the issue folder the workflow creates for the lesson,
 * {@code {lessons.dir}/11 - s02e01 Iterative refinement/}, so a lesson's inputs sit beside its
 * spec and plan. An exact {@code {id}} directory wins; two issue folders for one lesson is an
 * error rather than a guess.
 */
public record Lesson(Path dir) {

    private static final JavaPropsMapper PROPS = new JavaPropsMapper();

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public static Lesson of(LabsConfig config, String id) {
        Path root = config.lessonsDir();
        Path exact = root.resolve(id);
        if (Files.isDirectory(exact)) {
            return new Lesson(exact);
        }
        List<Path> led = issueFoldersLedBy(root, id);
        if (led.size() > 1) {
            throw new IllegalStateException(
                    "Lesson '" + id + "' is claimed by more than one folder: "
                            + led.stream().map(p -> p.getFileName().toString()).toList());
        }
        if (led.isEmpty()) {
            throw new IllegalStateException(
                    "No inputs for lesson '" + id + "' at " + exact + " or in a '{N} - " + id
                            + " ...' folder — check labs.lessons.dir in application.properties");
        }
        return new Lesson(led.getFirst());
    }

    /** Folders named {@code {N} - {id}} or {@code {N} - {id} {name}}, as the issue workflow creates. */
    private static List<Path> issueFoldersLedBy(Path root, String id) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        Pattern name = Pattern.compile("\\d+ - " + Pattern.quote(id) + "( .*)?");
        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(p -> name.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + root, e);
        }
    }

    /** Reads a prompt file as UTF-8. Pass the file name, e.g. {@code "system.md"}. */
    public String prompt(String fileName) {
        return read(dir.resolve(fileName));
    }

    /**
     * {@link #prompt}, refusing a file that is empty or whitespace only. Call it before the transcript
     * opens: a blank prompt would buy a run of a model that was told nothing.
     *
     * <p>A separate method rather than a change to {@code prompt}, so the refusal is opt-in: a runner
     * that reads a file which may legitimately be empty keeps reading it.
     *
     * @throws IllegalStateException when the file is blank, naming it and the lesson's folder
     */
    public String requiredPrompt(String fileName) {
        String prompt = prompt(fileName);
        if (prompt.isBlank()) {
            throw new IllegalStateException(
                    fileName + " in the lesson bundle '" + dir.getFileName() + "' is blank. Write it before running.");
        }
        return prompt;
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