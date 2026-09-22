package io.github.dbonkowska.dscribe.labs.s03e04;

import java.nio.charset.StandardCharsets;

/**
 * Enforces the exercise's byte range on a reply, measured on the wire rather than in characters.
 *
 * <p>Counting {@code String.length()} would pass an accented reply that overshoots the real byte
 * count the exercise checks, and this lesson's own replies are Polish city names — the case this
 * class exists to get right rather than the edge case that slips through.
 */
final class ReplyBounds {

    private ReplyBounds() {}

    /**
     * @return {@code text}, unchanged, once it is confirmed to fit
     * @throws IllegalArgumentException if {@code text}'s UTF-8 byte length falls outside
     *                                  {@code [minBytes, maxBytes]}
     */
    static String require(String text, int minBytes, int maxBytes) {
        int actual = text.getBytes(StandardCharsets.UTF_8).length;

        if (actual < minBytes) {
            throw new IllegalArgumentException(
                    "Reply is " + actual + " UTF-8 bytes, under the minimum of " + minBytes + ".");
        }
        if (actual > maxBytes) {
            throw new IllegalArgumentException(
                    "Reply is " + actual + " UTF-8 bytes, over the maximum of " + maxBytes + ".");
        }
        return text;
    }
}
