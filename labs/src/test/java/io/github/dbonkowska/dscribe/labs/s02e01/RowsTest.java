package io.github.dbonkowska.dscribe.labs.s02e01;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Written after a run that cost real money for a reason worth recording.
 *
 * <p>A configured column name that is not in the downloaded file used to surface from inside a
 * tool call. {@code Toolbox} turns anything a tool handler throws into a tool result, so the model
 * read a configuration error as its own mistake — and, being told the file's real column names by
 * the error itself, rewrote its prompt around them. The placeholder check then rejected *that*.
 * Two errors, neither one satisfiable, until the run was killed by hand.
 *
 * <p>So what this class owes is a refusal the operator can act on: which column was asked for,
 * what the file actually holds, and which key to change. The runner parses once at startup so it
 * lands before the first model call, where nothing can mistake it for something to reason about.
 *
 * <p>Column names here are invented. The real ones are supplied by the exercise and do not belong
 * in this repository — which is the rule the original wrong guess was made under.
 */
class RowsTest {

    private static final String CSV = """
            ref,label
            a1,a widget
            a2,"a gadget, boxed"
            """;

    @Test
    void readsEachRowIntoAnItem() {
        List<Item> rows = Rows.parse(CSV, "ref", "label");

        assertEquals(List.of(new Item("a1", "a widget"), new Item("a2", "a gadget, boxed")), rows);
    }

    /** The real descriptions carry commas inside quotes, so a naive split would have shipped. */
    @Test
    void keepsAQuotedFieldWhole() {
        assertEquals("a gadget, boxed", Rows.parse(CSV, "ref", "label").get(1).description());
    }

    @Test
    void refusesAnIdColumnTheFileDoesNotHave() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> Rows.parse(CSV, "nope", "label"));

        assertTrue(thrown.getMessage().contains("nope"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("ref"), "it has to say what the file does hold");
        assertTrue(thrown.getMessage().contains("data.idColumn"), "and which key to change");
    }

    @Test
    void refusesADescriptionColumnTheFileDoesNotHave() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> Rows.parse(CSV, "ref", "nope"));

        assertTrue(thrown.getMessage().contains("data.descriptionColumn"), thrown::getMessage);
    }

    /** A header-only file is not an error — it is a file with nothing in it yet. */
    @Test
    void readsAFileWithHeadersAndNoRows() {
        assertEquals(List.of(), Rows.parse("ref,label\n", "ref", "label"));
    }
}
