package io.github.dbonkowska.dscribe.labs.tokens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which encoding this counts with is invisible at the call site and wrong by a little rather than
 * loudly — exactly the failure the guard exists to prevent. A measurement that is quietly 10%
 * optimistic lets an over-long prompt through to a judge that charges to reject it, and one that
 * is quietly pessimistic makes the task look unwinnable with nothing saying why.
 *
 * <p>So the fixture is chosen to separate the encodings rather than to read nicely: the Polish
 * pangram below counts 11 under {@code o200k_base}, 12 under {@code cl100k_base} and 19 under
 * {@code r50k_base}. Swapping the registry lookup fails here instead of in a live run.
 *
 * <p>The samples are invented and belong to no lesson.
 */
class TokensTest {

    /** A pangram, not a lesson's data. Its accented characters are what make the spread wide. */
    private static final String POLISH = "zażółć gęślą jaźń";

    /** The same length in characters, no accents — so a difference can only come from encoding. */
    private static final String ASCII = "zazolc gesla jazn";

    @Test
    void countsWithO200kRatherThanAnOlderEncoding() {
        assertEquals(11, Tokens.count(POLISH),
                "12 means cl100k_base, 19 means r50k_base — both are the wrong registry lookup");
    }

    @Test
    void countsNothingForAnEmptyString() {
        assertEquals(0, Tokens.count(""));
    }

    /**
     * Guards against the cheap thing this could have been. Both samples are 17 characters, so a
     * length-based approximation would score them identically.
     */
    @Test
    void countsTokensRatherThanCharacters() {
        assertEquals(ASCII.length(), POLISH.length(), "the fixtures only work if they match in length");
        assertTrue(Tokens.count(POLISH) > Tokens.count(ASCII),
                () -> "accented text costs more tokens: " + Tokens.count(POLISH)
                        + " vs " + Tokens.count(ASCII));
    }
}
