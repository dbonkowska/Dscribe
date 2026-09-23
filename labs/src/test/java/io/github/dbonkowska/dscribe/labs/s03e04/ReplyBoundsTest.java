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

    /**
     * 4 characters, but each of {@code ł}, {@code ó}, {@code ź} is 2 bytes in UTF-8 — 7 bytes. The
     * bounds are chosen so the two counts land on opposite sides of them: with a range like
     * {@code [4, 500]} both counts sit inside it, and an implementation using
     * {@code String.length()} would pass unchanged.
     */
    @Test
    void acceptsTextWhoseByteCountClearsAFloorItsCharacterCountDoesNot() {
        assertDoesNotThrow(() -> ReplyBounds.require("łódź", 5, 500));
    }

    @Test
    void refusesTextWhoseByteCountBreaksACeilingItsCharacterCountKeeps() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> ReplyBounds.require("łódź", 4, 6));

        assertTrue(thrown.getMessage().contains("7"), thrown::getMessage);
    }
}
