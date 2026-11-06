package io.github.dbonkowska.dscribe.labs.s05e04;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wrong move is irreversible and the reply does not say why it failed, so the safety rules are
 * proved exhaustively rather than by example. Row 1 is the top row, so a left move lowers the row.
 *
 * <p>The grid sizes and command names are invented: nothing in this file is supplied by the exercise.
 */
class SteeringTest {

    private static final int ROWS = 5;
    private static final String LEFT = "l";
    private static final String AHEAD = "a";
    private static final String RIGHT = "r";
    private static final Map<String, Integer> SHIFT = Map.of(LEFT, -1, AHEAD, 0, RIGHT, 1);
    private static final Steering STEERING = new Steering(ROWS, AHEAD, LEFT, RIGHT);

    /** One combination of what a move is chosen from. */
    private record Situation(int row, int obstacle, Bearing bearing, int target) {

        Optional<String> move() {
            return STEERING.move(row, obstacle, bearing, target);
        }

        int landing(String move) {
            return row + SHIFT.get(move);
        }
    }

    private static void everySituation(Consumer<Situation> check) {
        for (int row : rows()) {
            for (int obstacle : rows()) {
                for (Bearing bearing : Bearing.values()) {
                    for (int target : rows()) {
                        check.accept(new Situation(row, obstacle, bearing, target));
                    }
                }
            }
        }
    }

    private static List<Integer> rows() {
        return IntStream.rangeClosed(1, ROWS).boxed().toList();
    }

    @Test
    void neverLeavesTheGrid() {
        everySituation(s -> s.move().ifPresent(move -> {
            assertTrue(s.landing(move) >= 1 && s.landing(move) <= ROWS, s::toString);
        }));
    }

    @Test
    void neverMovesTowardTheBearing() {
        everySituation(s -> s.move().ifPresent(move -> {
            if (s.bearing() == Bearing.LEFT) {
                assertNotEquals(LEFT, move, s::toString);
            }
            if (s.bearing() == Bearing.AHEAD) {
                assertNotEquals(AHEAD, move, s::toString);
            }
            if (s.bearing() == Bearing.RIGHT) {
                assertNotEquals(RIGHT, move, s::toString);
            }
        }));
    }

    /** A sideways move leaves through the current column, so its landing row must be free here too. */
    @Test
    void neverEntersTheCurrentObstaclesRow() {
        everySituation(s -> s.move().ifPresent(move ->
                assertNotEquals(s.obstacle(), s.landing(move), s::toString)));
    }

    /**
     * What the rules are for. A side bearing gives a direction and not a row, so the move must hold
     * against every row that side could mean.
     */
    @Test
    void survivesEveryObstacleTheBearingAllows() {
        everySituation(s -> {
            if (s.row() == s.obstacle()) {
                return;
            }
            s.move().ifPresent(move -> {
                for (int next : rows()) {
                    boolean allowed = switch (s.bearing()) {
                        case LEFT -> next < s.row();
                        case AHEAD -> next == s.row();
                        case RIGHT -> next > s.row();
                        case UNCLEAR -> false;
                    };
                    if (allowed) {
                        assertNotEquals(next, s.landing(move), () -> s + " next obstacle " + next);
                    }
                }
            });
        });
    }

    @Test
    void anUnclearBearingNeverMoves() {
        everySituation(s -> {
            if (s.bearing() == Bearing.UNCLEAR) {
                assertEquals(Optional.empty(), s.move(), s::toString);
            }
        });
    }

    @Test
    void reportsTheDeadEndAsNoMove() {
        assertEquals(Optional.empty(), STEERING.move(1, 2, Bearing.AHEAD, 3));
        assertEquals(Optional.empty(), STEERING.move(ROWS, ROWS - 1, Bearing.AHEAD, 3));
    }

    @Test
    void takesTheSafeMoveClosestToTheTarget() {
        assertEquals(Optional.of(RIGHT), STEERING.move(2, 1, Bearing.LEFT, 4));
        assertEquals(Optional.of(AHEAD), STEERING.move(2, 1, Bearing.LEFT, 2));
        assertEquals(Optional.of(LEFT), STEERING.move(3, 5, Bearing.RIGHT, 1));
        assertEquals(Optional.of(RIGHT), STEERING.move(3, 1, Bearing.AHEAD, 5));
    }

    /** Nothing lies beyond the edge, so such a label is a misread and must not steer the run. */
    @Test
    void aBearingOffTheGridIsAMisread() {
        assertTrue(STEERING.pointsOffGrid(1, Bearing.LEFT));
        assertTrue(STEERING.pointsOffGrid(ROWS, Bearing.RIGHT));
        assertFalse(STEERING.pointsOffGrid(2, Bearing.LEFT));
        assertFalse(STEERING.pointsOffGrid(ROWS - 1, Bearing.RIGHT));
        assertFalse(STEERING.pointsOffGrid(1, Bearing.AHEAD));
        assertFalse(STEERING.pointsOffGrid(ROWS, Bearing.AHEAD));
    }
}
