package io.github.dbonkowska.dscribe.labs.s02e01;

import io.github.dbonkowska.dscribe.labs.tokens.TokenBudget;
import io.github.dbonkowska.dscribe.labs.tokens.Tokens;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that stands in for an authority which charges to answer. Both of its failure
 * directions are silent: too permissive and an unsendable prompt is paid for anyway, too strict
 * and the exercise looks unwinnable with nothing saying why. Neither shows up as an exception in
 * a live run — one shows up as a wasted cycle, the other as a candidate that can never be tried.
 *
 * <p>Where the boundary sits moved to {@code TokenBudgetTest} along with the arithmetic. What
 * stays here is the part only rendering knows: the placeholders, and that the size checked is the
 * text once a row is filled in rather than the template.
 *
 * <p>Placeholders and fixtures are invented — they belong to no lesson.
 */
class RenderingTest {

    private static final String ID = "{ID}";
    private static final String DESC = "{DESC}";

    private static final String TEMPLATE = "Item {ID}: {DESC}. Reply about {ID}.";
    private static final Item ITEM = new Item("7", "widget");
    private static final String RENDERED = "Item 7: widget. Reply about 7.";

    @Test
    void substitutesEveryOccurrenceOfBothPlaceholders() {
        assertEquals(RENDERED, generous().render(TEMPLATE, ITEM));
    }

    @Test
    void refusesATemplateMissingAPlaceholder() {
        String missing = "Item {ID}. Reply.";

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> generous().requirePlaceholders(missing));

        assertTrue(thrown.getMessage().contains(DESC), thrown::getMessage);
    }

    /** Rendering enforces it too, so an unvalidated template cannot quietly send data-free text. */
    @Test
    void refusesToRenderATemplateMissingAPlaceholder() {
        assertThrows(IllegalArgumentException.class,
                () -> generous().render("Item {ID}. Reply.", ITEM));
    }

    /**
     * Only the wiring is asserted here. Where the boundary sits — inclusive, and tightened by the
     * margin — is {@code TokenBudgetTest}'s subject; what this owns is that a render over it is
     * refused, and that the refusal tells the model what it spent.
     */
    @Test
    void refusesARenderOverTheBudgetAndSaysWhatItMeasured() {
        int exact = Tokens.count(RENDERED);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Rendering(ID, DESC, new TokenBudget(exact + 2, 3)).render(TEMPLATE, ITEM));

        assertTrue(thrown.getMessage().contains(String.valueOf(exact)),
                () -> "the model has to be told what it spent: " + thrown.getMessage());
    }

    /**
     * The number reaches the model, inside the tool's description — so the one reported has to be
     * the budget's, not a second computation of it that could drift.
     */
    @Test
    void reportsTheCapItsBudgetEnforces() {
        assertEquals(95, new Rendering(ID, DESC, new TokenBudget(100, 5)).effectiveCap());
    }

    /** A cap nothing will bump into, for the cases that are not about size. */
    private static Rendering generous() {
        return new Rendering(ID, DESC, new TokenBudget(1_000, 0));
    }
}
