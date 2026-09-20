package io.github.dbonkowska.dscribe.labs.data;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

    /**
     * Unpacks an archive alongside it, once.
     *
     * <p>Skipped when the target already holds something: an archive is fetched once and unpacked
     * once, and a later run neither pays for the work again nor overwrites what an earlier one
     * produced.
     *
     * <p>Every entry name is resolved and checked <em>before</em> a single byte is written, and a
     * name landing outside the target refuses the whole archive rather than that one entry. Two
     * reasons for whole rather than one: a tampered archive is not something to partially trust,
     * and a partial extraction would leave the target non-empty, so the skip above would read it
     * as finished on the next run and never look again.
     *
     * <p>Names are decoded as UTF-8 explicitly. The platform default would mangle a non-ASCII
     * entry name into a different path on one machine and not another.
     */
    public void unzip(String archive, String intoDir) {
        Path target = file(intoDir);
        if (unpacked(target)) {
            return;
        }

        Path source = file(archive);
        Path root = target.toAbsolutePath().normalize();

        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zip.stream().toList();

            for (ZipEntry entry : entries) {
                Path destination = root.resolve(entry.getName()).normalize();
                if (!destination.startsWith(root)) {
                    throw new IllegalStateException(
                            "Refusing to unpack " + source + ": entry '" + entry.getName()
                                    + "' resolves to " + destination + ", outside " + root
                                    + ". Nothing was written.");
                }
            }

            for (ZipEntry entry : entries) {
                Path destination = root.resolve(entry.getName()).normalize();
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot unzip " + source, e);
        }
    }

    private static boolean unpacked(Path target) {
        if (!Files.isDirectory(target)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(target)) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot inspect " + target, e);
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