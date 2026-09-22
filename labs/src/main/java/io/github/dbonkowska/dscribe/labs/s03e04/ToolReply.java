package io.github.dbonkowska.dscribe.labs.s03e04;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the reply text from resolved city names, bounded to the exercise's byte range.
 *
 * <p>The calling agent stops its run on a reply it never receives, so a size violation here is
 * refused before it is sent nowhere — every branch below runs its output through
 * {@link ReplyBounds}, and truncation keeps whole names only. A partial name reads as a real,
 * different one; there is no way for whoever reads it to tell it was cut.
 */
final class ToolReply {

    /** Returned when nothing resolved. Never empty — a reply the agent cannot read stops its run. */
    static final String NO_MATCH = "No item matched that description. Try rephrasing the query.";

    /** Appended once the full list does not fit; fixed text, not a count — a count would need its
     *  own room reserved, one more thing that could itself overflow the cap. */
    static final String OVERFLOW_HINT = " — more results exist, narrow your query.";

    private static final String SEPARATOR = ", ";

    private ToolReply() {}

    static String of(List<String> names, int minBytes, int maxBytes) {
        if (names.isEmpty()) {
            return ReplyBounds.require(NO_MATCH, minBytes, maxBytes);
        }

        String full = String.join(SEPARATOR, names);
        if (utf8Length(full) <= maxBytes) {
            return ReplyBounds.require(full, minBytes, maxBytes);
        }

        StringBuilder kept = new StringBuilder();
        for (String name : names) {
            String candidate = (kept.isEmpty() ? name : kept + SEPARATOR + name) + OVERFLOW_HINT;
            if (utf8Length(candidate) > maxBytes) {
                break;
            }
            if (!kept.isEmpty()) {
                kept.append(SEPARATOR);
            }
            kept.append(name);
        }

        // not even one name plus the hint fits under the cap — nothing useful can be said
        if (kept.isEmpty()) {
            return ReplyBounds.require(NO_MATCH, minBytes, maxBytes);
        }

        return ReplyBounds.require(kept + OVERFLOW_HINT, minBytes, maxBytes);
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
