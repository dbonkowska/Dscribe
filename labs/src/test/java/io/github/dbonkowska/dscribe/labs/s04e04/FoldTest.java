package io.github.dbonkowska.dscribe.labs.s04e04;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hub takes ASCII only, in names and in text, and a name only in lowercase with underscores.
 * A fold that misses one letter does not fail — it sends a name the hub refuses, or a link whose
 * target was written under a different spelling.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class FoldTest {

    @Test
    void foldsEveryPolishDiacriticAndKeepsCase() {
        assertEquals("Zazolc gesla jazn", Fold.text("Zażółć gęślą jaźń"));
    }

    /** NFD leaves {@code ł} whole: it has no combining mark to strip, so it needs its own case. */
    @Test
    void foldsTheLetterWithNoDecomposition() {
        assertEquals("LANCUCH laka", Fold.text("ŁAŃCUCH łąka"));
    }

    @Test
    void passesAsciiThrough() {
        assertEquals("abc 123", Fold.text("abc 123"));
    }

    @Test
    void makesANameLowercaseWithUnderscores() {
        assertEquals("cma_lakowa", Fold.name("Ćma Łąkowa"));
    }

    @Test
    void stripsANameAndJoinsAnyRunOfWhitespaceWithOneUnderscore() {
        assertEquals("ala_kot", Fold.name("  Ala   Kot "));
    }
}
