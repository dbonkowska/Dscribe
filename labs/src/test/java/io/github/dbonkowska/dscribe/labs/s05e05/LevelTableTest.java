package io.github.dbonkowska.dscribe.labs.s05e05;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The level a jump needs comes from a table in the saved docs, read at startup. A row misread, or a
 * year read from the prose around the table, would send an operator to the wrong setting with
 * nothing to show it was wrong until the battery drained.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class LevelTableTest {

    /** The docs' layout: pairs of (year, level) repeated across a row, inside prose. */
    private static final String DOC = """
            # Notes

            Pick the level for the year. In prose, 4000 | 09 is not a row.

            | Y | L | Y | L |
            | --- | --- | --- | --- |
            | 4000 | 01 | 4001 | 07 |
            | 4002 | 3 | 4003 | 02 |

            After the table, 4004 | 05 is prose again.
            """;

    private static String refusal(String doc) {
        return assertThrows(IllegalStateException.class, () -> LevelTable.parse(doc)).getMessage();
    }

    @Test
    void mapsEachYearToItsLevel() {
        LevelTable table = LevelTable.parse(DOC);

        assertEquals(1, table.require(4000));
        assertEquals(7, table.require(4001));
        assertEquals(3, table.require(4002));
        assertEquals(2, table.require(4003));
    }

    /** A pair in prose is not a row: rows start with a bar, and only the table's are read. */
    @Test
    void ignoresPairsOutsideTheTable() {
        LevelTable table = LevelTable.parse(DOC);

        String message = assertThrows(IllegalStateException.class, () -> table.require(4004)).getMessage();
        assertTrue(message.contains("4004"), message);
    }

    /** The prose pair names a year that the table also has, and the table's level wins. */
    @Test
    void readsTheTableNotTheProseForTheSameYear() {
        assertEquals(1, LevelTable.parse(DOC).require(4000));
    }

    @Test
    void refusesADocWithNoTable() {
        String message = refusal("# Notes\n\nNo table, 4000 | 01 in prose.\n");

        assertTrue(message.contains("table"), message);
    }

    @Test
    void refusesAYearListedTwice() {
        String message = refusal(DOC.replace("| 4003 | 02 |", "| 4001 | 02 |"));

        assertTrue(message.contains("4001"), message);
    }

    /** An odd cell count leaves a year with no level, and every pair after it shifted by one. */
    @Test
    void refusesARowWithAnOddCellCount() {
        String message = refusal(DOC.replace("| 4002 | 3 | 4003 | 02 |", "| 4002 | 3 | 4003 |"));

        assertTrue(message.contains("4002"), message);
    }

    /** Below the separator every cell is a number, so a word there is a misread rather than a header. */
    @Test
    void refusesACellBelowTheSeparatorThatIsNotANumber() {
        String message = refusal(DOC.replace("| 4002 | 3 |", "| 4002 | three |"));

        assertTrue(message.contains("three"), message);
    }

    @Test
    void refusesAnAbsentYearByName() {
        LevelTable table = LevelTable.parse(DOC);

        String message = assertThrows(IllegalStateException.class, () -> table.require(5123)).getMessage();
        assertTrue(message.contains("5123"), message);
    }
}
