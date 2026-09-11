package io.github.dbonkowska.dscribe.labs.s02e01;

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
 * <p>The boundary cases measure the fixture with {@link Tokens} rather than pinning a literal.
 * A magic number here would be a second, quieter assertion about the encoding, which
 * {@link TokensTest} already owns; what these need to assert is the comparison, not the count.
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

    @Test
    void acceptsARenderThatExactlyFitsTheEffectiveCap() {
        int exact = Tokens.count(RENDERED);

        assertEquals(RENDERED, new Rendering(ID, DESC, exact + 3, 3).render(TEMPLATE, ITEM),
                "the boundary is inclusive — a candidate that exactly fits is sendable");
    }

    @Test
    void refusesARenderOneTokenOverTheEffectiveCap() {
        int exact = Tokens.count(RENDERED);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Rendering(ID, DESC, exact + 2, 3).render(TEMPLATE, ITEM));

        assertTrue(thrown.getMessage().contains(String.valueOf(exact)),
                () -> "the model has to be told what it spent: " + thrown.getMessage());
    }

    /**
     * The number reaches the model, inside the tool's description, so a wrong one does not merely
     * mis-guard — it tells the model to aim at a size that is not the one being enforced.
     */
    @Test
    void reportsTheCapItActuallyEnforces() {
        assertEquals(95, new Rendering(ID, DESC, 100, 5).effectiveCap());
    }

    /**
     * The direction most likely to be got backwards. A margin that widened the cap would make the
     * guard weaker exactly where it was meant to be more cautious.
     */
    @Test
    void marginTightensTheCapRatherThanLooseningIt() {
        int exact = Tokens.count(RENDERED);

        assertEquals(RENDERED, new Rendering(ID, DESC, exact, 0).render(TEMPLATE, ITEM));
        assertThrows(IllegalArgumentException.class,
                () -> new Rendering(ID, DESC, exact, 1).render(TEMPLATE, ITEM));
    }

    /** A cap nothing will bump into, for the cases that are not about size. */
    private static Rendering generous() {
        return new Rendering(ID, DESC, 1_000, 0);
    }
}
