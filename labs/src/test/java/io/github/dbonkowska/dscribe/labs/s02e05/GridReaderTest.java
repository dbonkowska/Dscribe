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
 * <p>The coordinate itself cannot be verified — that is the whole problem. Two things that can be
 * are: the size of the grid, which the bundle knows and the model is not told, and whether the
 * model says the same thing twice. The first catches a grid counted wrong from the outside. The
 * second catches a position counted wrong inside a grid counted right, which the first cannot see.
 *
 * <p>Deliberately not proof. Two readings from one model can be wrong the same way, and this sees
 * nothing when they are. It catches an unsteady reading, not a consistently biased one.
 *
 * <p>Driven by a scripted seam rather than a model, so what is asserted is the decision rather than
 * a provider's behaviour. The numbers are invented — they are not this exercise's grid.
 */
class GridReaderTest {

    /** 9 by 6, and three attempts before giving up. Each attempt reads twice. */
    private static final TaskParams.Grid GRID = new TaskParams.Grid(9, 6, 3);

    private static final GridReader.Reading AGREES = reading(9, 6, 5, 4);
    private static final GridReader.Reading OTHER_TARGET = reading(9, 6, 4, 5);
    private static final GridReader.Reading MISCOUNTED_GRID = reading(8, 6, 5, 4);

    private int calls;

    private static GridReader.Reading reading(int columns, int rows, int targetColumn, int targetRow) {
        return new GridReader.Reading("because", columns, rows, targetColumn, targetRow);
    }

    private GridReader reader(GridReader.Reading... queued) {
        Deque<GridReader.Reading> remaining = new ArrayDeque<>(List.of(queued));
        return new GridReader(GRID, () -> {
            calls++;
            return remaining.poll();
        });
    }

    @Test
    void acceptsWhenBothReadingsAgreeWithTheGridAndWithEachOther() {
        GridReader.Reading accepted = reader(AGREES, AGREES).read();

        assertEquals(5, accepted.targetColumn());
        assertEquals(4, accepted.targetRow());
        assertEquals(2, calls, "one attempt is two readings, and no more");
    }

    /**
     * The failure the grid size cannot see: both readings count the grid correctly and place the
     * target in different cells of it. Transposition looks exactly like this.
     */
    @Test
    void readsAgainWhenTheTwoReadingsPlaceTheTargetDifferently() {
        GridReader.Reading accepted = reader(AGREES, OTHER_TARGET, AGREES, AGREES).read();

        assertEquals(5, accepted.targetColumn());
        assertEquals(4, calls, "a disagreement costs a whole attempt, not one reading");
    }

    /** A grid counted wrong from the start makes every position inside it wrong too. */
    @Test
    void readsAgainWhenAReadingDisagreesWithTheKnownGrid() {
        GridReader.Reading accepted = reader(MISCOUNTED_GRID, AGREES, AGREES, AGREES).read();

        assertEquals(9, accepted.gridColumns());
        assertEquals(4, calls);
    }

    /** The second of a pair is checked too, not only the first. */
    @Test
    void readsAgainWhenOnlyTheSecondReadingDisagreesWithTheKnownGrid() {
        GridReader.Reading accepted = reader(AGREES, MISCOUNTED_GRID, AGREES, AGREES).read();

        assertEquals(9, accepted.gridColumns());
        assertEquals(4, calls);
    }

    /**
     * Giving up is the point: the run ends here, before a single hub call, rather than submitting a
     * sequence built on a number nothing confirmed.
     */
    @Test
    void failsOnceEveryAttemptDisagrees() {
        GridReader reader = reader(
                AGREES, OTHER_TARGET,
                AGREES, OTHER_TARGET,
                AGREES, OTHER_TARGET);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, reader::read);

        assertEquals(6, calls, "three attempts of two readings each, and no more");
        assertTrue(thrown.getMessage().toLowerCase().contains("target"),
                () -> "it has to say the two readings differed on the target: " + thrown.getMessage());
    }

    /** The same ending by the other route, so the two cannot be confused in a log. */
    @Test
    void failsNamingTheGridWhenEveryReadingMiscountsIt() {
        GridReader reader = reader(
                MISCOUNTED_GRID, MISCOUNTED_GRID,
                MISCOUNTED_GRID, MISCOUNTED_GRID,
                MISCOUNTED_GRID, MISCOUNTED_GRID);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, reader::read);

        assertEquals(6, calls);
        assertTrue(thrown.getMessage().contains("8") && thrown.getMessage().contains("9"),
                () -> "it has to say what was counted and what was expected: " + thrown.getMessage());
    }
}
