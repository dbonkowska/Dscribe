package io.github.dbonkowska.dscribe.labs.data;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One lesson's runtime files — what it downloaded, and what it produced for the next lesson to
 * read. Every path under {@code {dataDir}/{episode}} is built here, so no caller computes local
 * layout for itself.
 *
 * <p>Not called {@code LessonData}: {@link io.github.dbonkowska.dscribe.labs.lesson.Lesson} is
 * the read-only bundle outside the repository, and two names one word apart with opposite
 * properties is a trap.
 *
 * <p>Lessons couple to each other through these files rather than through each other's Java
 * types — a runner is never revisited once its flag is earned.
 */
public record Artifacts(Path dir) {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // a later lesson reads only the fields it needs out of a richer file
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // these are files a human opens when a run goes wrong
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    public static Artifacts of(Path root, String episode) {
        return new Artifacts(root.resolve(episode));
    }

    public Path file(String name) {
        return dir.resolve(name);
    }

    public <T> T read(String name, Class<T> type) {
        Path path = file(name);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return MAPPER.readValue(reader, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    public <T> List<T> readList(String name, Class<T> element) {
        Path path = file(name);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return MAPPER.readerForListOf(element).readValue(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    public void write(String name, Object value) {
        Path path = file(name);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                MAPPER.writeValue(writer, value);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + path, e);
        }
    }
}