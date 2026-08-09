package io.github.dbonkowska.dscribe.labs.s01e01;

import java.util.List;

/**
 * The model's reply. {@code tags} is {@code List<String>} rather than an enum because the
 * vocabulary is exercise content and lives in the lesson bundle — see
 * {@link S01E01#tagSchema}, which constrains these strings at the schema level instead.
 */
public record TagResult(List<Entry> results) {
    public record Entry(int id, List<String> tags) {}
}