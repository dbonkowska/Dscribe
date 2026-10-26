package io.github.dbonkowska.dscribe.labs.s05e02;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Whether a synthesised line can be heard the way it was written, judged on the words a listener
 * must not mishear. A miss here costs a re-synthesis; sent, it costs a session.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class ClarityTest {

    private static final List<String> CANDIDATES = List.of("AB12", "AB34", "CD56");

    @Test
    void findsNothingMissingWhenEveryCandidateSaidIsHeard() {
        assertEquals(List.of(), Clarity.missing("status AB12, AB34, CD56", "Status AB12, AB34, CD56.", CANDIDATES));
    }

    /** A listener writing a code as two words, or with a dash, has still heard it. */
    @Test
    void toleratesSpacesHyphensAndCaseInWhatWasHeard() {
        assertEquals(List.of(), Clarity.missing("status AB12 i AB34", "status ab 12 i AB-34", CANDIDATES));
    }

    /** The miss that burnt a session: a letter run into the code makes it a different one. */
    @Test
    void namesACandidateThatWasSaidButHeardAsSomethingElse() {
        assertEquals(List.of("CD56"), Clarity.missing("AB12, AB34 i CD56", "AB 12, AB 34 i ECD 56", CANDIDATES));
    }

    /** Only what the line says is checked: a candidate it never named cannot be misheard. */
    @Test
    void ignoresCandidatesTheLineDoesNotName() {
        assertEquals(List.of(), Clarity.missing("only AB12", "only AB 12", CANDIDATES));
    }
}
