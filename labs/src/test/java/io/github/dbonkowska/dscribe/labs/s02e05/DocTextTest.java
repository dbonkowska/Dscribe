package io.github.dbonkowska.dscribe.labs.s02e05;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reaches the model when the run fetches documentation written for a browser.
 *
 * <p>The failure this prevents is quiet rather than loud. A stylesheet left in place is a page of
 * selectors and colour values the model pays for on every round of the loop, reads as part of its
 * instructions, and can find nothing to do with. Nothing errors; the run simply costs more and
 * reasons over noise.
 *
 * <p>The markup here is invented: nothing in this file is supplied by the exercise.
 */
class DocTextTest {

    private static final String PAGE = """
            <html>
            <head>
            <style>
            body { margin: 0; font-family: Verdana; }
            </style>
            <script>
            var counter = 0;
            </script>
            </head>
            <body>
            <h1>Widget API</h1>
            <p>Set the height first.</p>
            <div class="card">
            <table>
            <tbody>
            <tr><th>Method</th><th>Notes</th></tr>
            <tr><td>lift(4u)</td><td>Sets the height.</td></tr>
            </tbody>
            </table>
            </div>
            </body>
            </html>
            """;

    /** A stylesheet survives tag-stripping as text unless its whole block goes first. */
    @Test
    void removesStyleBlocksWithTheirContents() {
        String stripped = DocText.strip(PAGE);

        assertFalse(stripped.contains("margin"), () -> stripped);
        assertFalse(stripped.contains("Verdana"), () -> stripped);
    }

    @Test
    void removesScriptBlocksWithTheirContents() {
        assertFalse(DocText.strip(PAGE).contains("counter"), () -> DocText.strip(PAGE));
    }

    /** HTML is case-insensitive, and a page written the other way round is still a page. */
    @Test
    void removesBlocksWhateverCaseTheyAreWrittenIn() {
        String stripped = DocText.strip("<STYLE>body { margin: 0; }</STYLE><p>kept</p>");

        assertFalse(stripped.contains("margin"), () -> stripped);
        assertTrue(stripped.contains("kept"), () -> stripped);
    }

    /** The prose is the point: every word of it, in the order the page had it, and no markup. */
    @Test
    void keepsTheTextBetweenTagsInOrderAndDropsTheTags() {
        String stripped = DocText.strip(PAGE);

        assertFalse(stripped.contains("<"), () -> "no markup should survive: " + stripped);
        assertTrue(stripped.contains("Widget API"), () -> stripped);
        assertTrue(stripped.contains("Sets the height."), () -> stripped);
        assertTrue(stripped.indexOf("Widget API") < stripped.indexOf("Set the height first."),
                () -> "the page's order is the documentation's order: " + stripped);
    }

    /**
     * A tag becomes a space rather than nothing. A table row written on one line would otherwise
     * run its cells together into a word the page never contained, and the model would read that
     * word as the API's own vocabulary.
     */
    @Test
    void keepsCellsOfAOneLineRowApart() {
        String stripped = DocText.strip(PAGE);

        assertTrue(stripped.contains("Method Notes"),
                () -> "cells of one row must not be joined into MethodNotes: " + stripped);
    }

    /** Stripping a block element per line leaves the gaps behind; they are paid for as tokens. */
    @Test
    void collapsesRunsOfBlankLines() {
        assertFalse(DocText.strip(PAGE).contains("\n\n\n"), () -> DocText.strip(PAGE));
    }

    @Test
    void leavesTextWithoutMarkupUnchanged() {
        assertEquals("just words", DocText.strip("just words"));
    }
}
