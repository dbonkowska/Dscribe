package io.github.dbonkowska.dscribe.labs.s02e01;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a downloaded file into rows, refusing a file whose columns are not the configured ones.
 *
 * <p>The refusal is the point of this class existing separately. Left inside the tool call, a
 * column name that does not match the file surfaces as a {@code RuntimeException} — which
 * {@code Toolbox} converts into a tool result, so the model reads a configuration error as its own
 * mistake and rewrites its prompt around the column names the error just disclosed. That is
 * unsatisfiable by construction, because the placeholder check then rejects the rewrite.
 *
 * <p>Nothing here can prevent that on its own: an exception thrown inside a handler is caught
 * whatever its type. What prevents it is *where* this is called — the runner parses once at
 * startup, so a misconfigured column fails before the first model call. The message is written for
 * whoever reads it there: which column was asked for, what the file actually holds, and which key
 * to change.
 */
final class Rows {

    private Rows() {}

    static List<Item> parse(String csv, String idColumn, String descriptionColumn) {
        try (var parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csv))) {

            List<String> headers = parser.getHeaderNames();
            require(headers, idColumn, "data.idColumn");
            require(headers, descriptionColumn, "data.descriptionColumn");

            List<Item> items = new ArrayList<>();
            for (CSVRecord record : parser) {
                items.add(new Item(record.get(idColumn), record.get(descriptionColumn)));
            }
            return items;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the downloaded rows", e);
        }
    }

    /**
     * Checked against the header rather than left to {@code record.get}, which throws the same
     * class of error once per row and words it for a library user rather than for whoever has to
     * edit the file.
     */
    private static void require(List<String> headers, String column, String key) {
        if (!headers.contains(column)) {
            throw new IllegalStateException(
                    "No column '" + column + "' in the downloaded file, which has " + headers
                            + ". Set " + key + " in the lesson's task.properties.");
        }
    }
}
