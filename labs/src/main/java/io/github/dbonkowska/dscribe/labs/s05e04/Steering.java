package io.github.dbonkowska.dscribe.labs.s05e04;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which move is safe, given where the next obstacle was said to be.
 *
 * <p>Pure, and the only place a move is chosen. Row 1 is the top row, and every move advances one
 * column, so a move's landing row is what has to be free. Two rules come from how the game behaves:
 *
 * <ul>
 *   <li>A side bearing gives a direction and not a row, so a move toward it is never chosen, even
 *       one that might land clear.
 *   <li>A sideways move also crashes if its landing row holds the current column's obstacle.
 * </ul>
 *
 * @param rows  the grid's height
 * @param ahead the command that keeps the row
 * @param left  the command that lowers the row by one
 * @param right the command that raises the row by one
 */
record Steering(int rows, String ahead, String left, String right) {

    private record Move(String command, int landing, Bearing toward) {}

    /**
     * The safe move closest to {@code targetRow}, or empty when none is safe or the bearing is
     * unknown. A tie goes to the first of left, ahead, right.
     */
    Optional<String> move(int row, int obstacleRow, Bearing bearing, int targetRow) {
        if (bearing == Bearing.UNCLEAR) {
            return Optional.empty();
        }
        return moves(row).stream()
                .filter(move -> move.toward() != bearing)
                .filter(move -> onGrid(move.landing()) && move.landing() != obstacleRow)
                .min(Comparator.comparingInt(move -> Math.abs(move.landing() - targetRow)))
                .map(Move::command);
    }

    /** Nothing lies beyond the edge, so a bearing that points there is a misread, not a direction. */
    boolean pointsOffGrid(int row, Bearing bearing) {
        return moves(row).stream()
                .anyMatch(move -> move.toward() == bearing && !onGrid(move.landing()));
    }

    private List<Move> moves(int row) {
        return List.of(
                new Move(left, row - 1, Bearing.LEFT),
                new Move(ahead, row, Bearing.AHEAD),
                new Move(right, row + 1, Bearing.RIGHT));
    }

    private boolean onGrid(int row) {
        return row >= 1 && row <= rows;
    }
}
