package io.github.dbonkowska.dscribe.labs.tokens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic every guard in front of a charging authority shares. Both of its failure
 * directions are silent: too permissive and something unsendable is paid for anyway, too strict and
 * the exercise looks unwinnable with nothing saying why. Neither surfaces as an exception in a live
 * run — one is a wasted submission, the other a candidate that can never be tried.
 *
 * <p>The boundary cases measure the fixture rather than pinning a literal. A magic number here
 * would be a second, quieter assertion about the encoding, which {@code TokensTest} already owns;
 * what these need to assert is the comparison, not the count.
 *
 * <p>The sample is invented and belongs to no lesson.
 */
class TokenBudgetTest {

    private static final String TEXT = "Item 7: widget. Reply about 7.";

    @Test
    void measuresWithTheSharedCount() {
        assertEquals(Tokens.count(TEXT), new TokenBudget(1_000, 0).measure(TEXT));
    }

    /**
     * The number reaches the model, inside a tool's description, so a wrong one does not merely
     * mis-guard — it tells the model to aim at a size that is not the one being enforced.
     */
    @Test
    void reportsTheCapItActuallyEnforces() {
        assertEquals(95, new TokenBudget(100, 5).effectiveCap());
    }

    @Test
    void acceptsTextThatExactlyFitsTheEffectiveCap() {
        int exact = Tokens.count(TEXT);

        assertTrue(new TokenBudget(exact + 3, 3).fits(exact),
                "the boundary is inclusive — something that exactly fits is sendable");
    }

    @Test
    void refusesTextOneTokenOverTheEffectiveCap() {
        int exact = Tokens.count(TEXT);

        assertFalse(new TokenBudget(exact + 2, 3).fits(exact));
    }

    /**
     * The direction most likely to be got backwards. A margin that widened the cap would make the
     * guard weaker exactly where it was meant to be more cautious.
     */
    @Test
    void marginTightensTheCapRatherThanLooseningIt() {
        int exact = Tokens.count(TEXT);

        assertTrue(new TokenBudget(exact, 0).fits(exact));
        assertFalse(new TokenBudget(exact, 1).fits(exact));
    }

    /** A cap nobody wrote binds to zero, and every candidate is then refused for existing. */
    @Test
    void refusesACapThatIsNotPositive() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBudget(0, 0));
    }

    @Test
    void refusesANegativeMargin() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBudget(40, -1));
    }

    /** Held back from the cap, so a margin as large as the cap leaves nothing that could be sent. */
    @Test
    void refusesAMarginThatWouldLeaveNothingSendable() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBudget(40, 40));
    }
}
