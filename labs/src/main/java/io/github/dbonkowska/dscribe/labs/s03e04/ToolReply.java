package io.github.dbonkowska.dscribe.labs.s03e04;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the reply text from resolved city names, bounded to the exercise's byte range.
 *
 * <p>The caller stops its run on a reply it never receives, so a size violation here is refused
 * before anything is sent — every branch below runs its output through {@link ReplyBounds}, and
 * truncation keeps whole names only. A partial name reads as a real, different one; there is no
 * way for whoever reads it to tell it was cut.
 */
final class ToolReply {

    /** Returned when nothing resolved. Never empty — a reply the agent cannot read stops its run. */
    static final String NO_MATCH = "No item matched that description. Try rephrasing the query.";

    /** Appended once the full list does not fit; fixed text, not a count — a count would need its
     *  own room reserved, one more thing that could itself overflow the cap. */
    static final String OVERFLOW_HINT = " — more results exist, narrow your query.";

    /**
     * Returned when the cap holds the hint but not one name beside it. Not {@link #NO_MATCH}:
     * something did match, there is just no room to list it, and telling the caller to rephrase
     * would send it away from a query that was fine.
     */
    static final String TOO_MANY = "Too many results to list, narrow your query.";

    /**
     * Returned when resolving the query failed on our side. Not {@link #NO_MATCH}, for the same
     * reason: a transient error is not a wording problem, and a caller told to rephrase will
     * rewrite a query that only needed sending again.
     */
    static final String LOOKUP_FAILED = "The lookup failed on our side. Try the same query again.";

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

        // not even one name plus the hint fits under the cap
        if (kept.isEmpty()) {
            return ReplyBounds.require(TOO_MANY, minBytes, maxBytes);
        }

        return ReplyBounds.require(kept + OVERFLOW_HINT, minBytes, maxBytes);
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
