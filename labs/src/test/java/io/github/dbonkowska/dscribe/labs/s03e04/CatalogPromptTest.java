package io.github.dbonkowska.dscribe.labs.s03e04;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template that silently ignores a value it has no slot for fails by omission: the prompt is
 * well-formed, every line is present, and the catalog is simply not in it. Every query then
 * resolves to nothing, with a confident explanation of why.
 */
class CatalogPromptTest {

    @Test
    void fillsTheSlotWithTheCatalog() {
        String rendered = CatalogPrompt.render("Match against:\n\n%s\n", "Widget A,I1\nWidget B,I2");

        assertEquals("Match against:\n\nWidget A,I1\nWidget B,I2\n", rendered);
    }

    /** The catalog is an argument, never part of the template, so its own {@code %} is inert. */
    @Test
    void leavesPercentSignsInTheCatalogAlone() {
        String rendered = CatalogPrompt.render("%s", "Widget 1% tolerance %s");

        assertEquals("Widget 1% tolerance %s", rendered);
    }

    @Test
    void refusesATemplateWithNoSlot() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> CatalogPrompt.render("no slot here", "catalog"));

        assertTrue(thrown.getMessage().contains("system.md"), thrown::getMessage);
    }

    /** {@code %%s} looks like a slot to anyone counting them and fills nothing. */
    @Test
    void refusesATemplateWhoseOnlySlotIsEscaped() {
        assertThrows(IllegalStateException.class, () -> CatalogPrompt.render("literal %%s only", "catalog"));
    }

    /** One catalog and two slots: {@code formatted} would throw on the second, mid-startup. */
    @Test
    void refusesATemplateWithMoreSlotsThanOneCatalogCanFill() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> CatalogPrompt.render("%s and %s", "catalog"));

        assertTrue(thrown.getMessage().contains("system.md"), thrown::getMessage);
    }
}
