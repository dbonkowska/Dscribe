package io.github.dbonkowska.dscribe.labs.s02e05;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only check standing between a miscounted image and a well-formed sequence that acts in the
 * wrong place.
 *
 * <p>The coordinate itself cannot be verified — that is the whole problem. So two things that can
 * be are verified instead, and neither is disclosed to the model: the size of the grid it counted,
 * and the position of a landmark whose answer is already known. Totals catch a grid counted wrong
 * from the start; the anchor catches a position counted wrong inside a grid counted right. A
 * reading that misses the landmark we can check has said nothing worth acting on about the one we
 * cannot.
 *
 * <p>Driven by a scripted seam rather than a model: the fake returns a queued reading per call, so
 * what is asserted is the decision, not a provider's behaviour. The numbers are invented.
 */
class GridReaderTest {

    /** 9 by 6, the landmark at column 2 row 3, and three attempts before giving up. */
    private static final TaskParams.Grid GRID = new TaskParams.Grid(9, 6, 2, 3, 3);

    private static final GridReader.Reading AGREES = reading(9, 6, 2, 3);
    private static final GridReader.Reading MISCOUNTED_GRID = reading(8, 6, 2, 3);
    private static final GridReader.Reading MISPLACED_ANCHOR = reading(9, 6, 4, 1);

    private int calls;

    private static GridReader.Reading reading(int columns, int rows, int anchorColumn, int anchorRow) {
        return new GridReader.Reading(columns, rows, anchorColumn, anchorRow, 5, 4, "because");
    }

    private GridReader reader(GridReader.Reading... queued) {
        Deque<GridReader.Reading> remaining = new ArrayDeque<>(List.of(queued));
        return new GridReader(GRID, () -> {
            calls++;
            return remaining.poll();
        });
    }

    @Test
    void acceptsAReadingThatAgreesOnBothTotalsAndAnchor() {
        GridReader.Reading accepted = reader(AGREES).read();

        assertEquals(5, accepted.targetColumn());
        assertEquals(4, accepted.targetRow());
        assertEquals(1, calls, "a reading that agrees is not read twice");
    }

    /** A grid counted wrong from the start: every position inside it is then wrong too. */
    @Test
    void readsAgainWhenTheTotalsDisagree() {
        GridReader.Reading accepted = reader(MISCOUNTED_GRID, AGREES).read();

        assertEquals(9, accepted.gridColumns());
        assertEquals(2, calls);
    }

    /** The grid is right and the counting inside it is not — what the totals cannot see. */
    @Test
    void readsAgainWhenTheAnchorDisagrees() {
        GridReader.Reading accepted = reader(MISPLACED_ANCHOR, AGREES).read();

        assertEquals(2, accepted.anchorColumn());
        assertEquals(2, calls);
    }

    /**
     * Giving up is the point: the run fails here, before a single hub call, rather than submitting
     * a sequence built on a number nothing agreed with.
     */
    @Test
    void failsOnceEveryAttemptDisagreesOnTheTotals() {
        GridReader reader = reader(MISCOUNTED_GRID, MISCOUNTED_GRID, MISCOUNTED_GRID);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, reader::read);

        assertEquals(3, calls, "it uses every attempt it was given, and no more");
        assertTrue(thrown.getMessage().contains("8") && thrown.getMessage().contains("9"),
                () -> "it has to say what was counted and what was expected: " + thrown.getMessage());
    }

    /** The same ending by the other route, so the two failures cannot be confused in a log. */
    @Test
    void failsNamingTheAnchorWhenEveryAttemptMisplacesIt() {
        GridReader reader = reader(MISPLACED_ANCHOR, MISPLACED_ANCHOR, MISPLACED_ANCHOR);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, reader::read);

        assertEquals(3, calls);
        assertTrue(thrown.getMessage().toLowerCase().contains("anchor"),
                () -> "it has to say the landmark was the disagreement: " + thrown.getMessage());
    }
}
