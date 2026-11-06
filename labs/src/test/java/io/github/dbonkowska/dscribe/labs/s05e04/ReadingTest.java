package io.github.dbonkowska.dscribe.labs.s05e04;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This is the gate in front of every move, and it has two opposite ways to be wrong. Reading a
 * garbled refusal as clear loses the game, and failing to read a clear one abandons a good game. So
 * a reply is positively one thing or it is {@link Reading.Unreadable}, and a re-read never helps.
 *
 * <p>Keys, values and garbling here are invented: nothing in this file is supplied by the exercise.
 */
class ReadingTest {

    private static final Reading.Decoder DECODER =
            new Reading.Decoder("all fine", "tune", "token", List.of("mn", "o0"));

    @ParameterizedTest
    @ValueSource(strings = {
            "\"all fine\"",
            "\"ALL FINE\"",
            "\"aLl   fInE\"",
            "\"All fiiiiine!\"",
            "\"a'll FiNeEeEe!\"",
            "\"all fime\""})
    void readsAPositivelyClearReply(String body) {
        assertEquals(new Reading.Clear(), DECODER.read(body));
    }

    @Test
    void readsARefusalThroughSwappedLettersMixedQuotesAndAMissingComma() {
        String body = """
                {
                    'tUmE": 137
                    "wrAp`: {
                        `T0KeN': "aB3dE0"
                        "kind": `some-thing else"
                    },
                    "fLag': true
                }""";

        assertEquals(new Reading.Targeted(137, "aB3dE0"), DECODER.read(body));
    }

    /** The digest is taken over the value as it stands, so folding it would lose the game. */
    @Test
    void neverFoldsAValue() {
        String body = "{\"tune\": 7, \"token\": \"n0mOo\"}";

        assertEquals(new Reading.Targeted(7, "n0mOo"), DECODER.read(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"tune\": \"many\", \"token\": \"abc\"}",
            "{\"tune\": 7}",
            "{\"tune\": 7, \"token\": \"\"}",
            "{\"token\": \"abc\"}",
            "{\"code\": 1, \"message\": \"all fine\"}",
            "error"})
    void refusesAReplyItCannotPositivelyRead(String body) {
        assertEquals(new Reading.Unreadable(), DECODER.read(body));
    }

    /** Near enough for a person, and exactly the reply that must not open the gate. */
    @ParameterizedTest
    @ValueSource(strings = {"\"all fined\"", "\"all five\"", "\"all\"", "\"fine\""})
    void aSimilarWordIsNotClear(String body) {
        assertEquals(new Reading.Unreadable(), DECODER.read(body));
    }

    @Test
    void digestsTheCodeWithTheSuffixAppended() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                new Reading.Targeted(1, "abc").digest("SHA-1", ""));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                new Reading.Targeted(1, "ab").digest("SHA-1", "c"));
    }
}
