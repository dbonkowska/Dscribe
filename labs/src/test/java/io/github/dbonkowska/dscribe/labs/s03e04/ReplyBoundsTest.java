package io.github.dbonkowska.dscribe.labs.s03e04;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Counts UTF-8 bytes, not {@code String.length()} — the two diverge for anything outside ASCII,
 * and the exercise's cap is stated in bytes.
 */
class ReplyBoundsTest {

    @Test
    void refusesTextShorterThanTheMinimum() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> ReplyBounds.require("abc", 4, 500));

        assertTrue(thrown.getMessage().contains("3"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("4"), thrown::getMessage);
    }

    @Test
    void acceptsTextAtExactlyTheMinimum() {
        assertEquals("abcd", ReplyBounds.require("abcd", 4, 500));
    }

    @Test
    void acceptsTextAtExactlyTheMaximum() {
        String text = "a".repeat(500);
        assertDoesNotThrow(() -> ReplyBounds.require(text, 4, 500));
    }

    @Test
    void refusesTextLongerThanTheMaximum() {
        String text = "a".repeat(501);

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> ReplyBounds.require(text, 4, 500));

        assertTrue(thrown.getMessage().contains("501"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("500"), thrown::getMessage);
    }

    /** 4 characters, but each of {@code ł}, {@code ó}, {@code ź} is 2 bytes in UTF-8 — 7 bytes total. */
    @Test
    void countsUtf8BytesRatherThanCharacters() {
        assertDoesNotThrow(() -> ReplyBounds.require("łódź", 4, 500));
    }
}
