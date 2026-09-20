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
import java.util.Comparator;
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
     * name landing outside the target refuses the whole archive rather than that one entry: a
     * tampered archive is not something to trust the rest of.
     *
     * <p>Names are decoded as UTF-8 explicitly. That states the intent rather than fixing a bug —
     * {@code ZipFile} already defaults to UTF-8 for entry names — and what it guards against is
     * someone later passing a charset that is not.
     *
     * <p>Entries are written into a staging directory beside the target and moved into place once
     * the last one lands, so the target only ever exists complete. The skip above makes that
     * necessary rather than tidy: extracting in place, an unpack interrupted half-way — a Ctrl-C,
     * a full disk, a killed process — would leave the target non-empty and short, and every later
     * run would read it as finished. A corpus missing half its records then submits a plausible
     * answer to an oracle that names nothing. Checking every entry before writing any closes that
     * for a hostile archive; only the move closes it for an interrupted one.
     */
    public void unzip(String archive, String intoDir) {
        Path target = file(intoDir);
        if (unpacked(target)) {
            return;
        }

        Path source = file(archive);
        Path staging = file(intoDir + ".unpacking");
        Path root = staging.toAbsolutePath().normalize();

        // whatever a killed run left under the staging name is partial by definition
        deleteTree(staging);

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

            // created up front rather than by the first entry's parent: an archive with no entries
            // would otherwise never create it, and the move below would fail naming an internal
            // path instead of saying anything about the archive
            Files.createDirectories(staging);

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

            // an empty target is not a finished unpack, but it would still block the move
            if (Files.isDirectory(target)) {
                Files.delete(target);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            throw new UncheckedIOException("Cannot unzip " + source, e);
        }
    }

    /**
     * Removes a directory and everything under it, tolerating its absence.
     *
     * <p>Only ever applied to the staging path, which this class owns and nothing else reads.
     */
    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot clear " + dir, e);
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