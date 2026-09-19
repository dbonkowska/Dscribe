package io.github.dbonkowska.dscribe.labs.s02e05;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a position off an image, and accepts it only once two things that can be checked agree.
 *
 * <p>The position itself cannot be verified — that is the whole problem this class exists for. A
 * transposed or miscounted coordinate still matches its shape, still renders a well-formed command,
 * and still reaches the hub as a sequence that simply acts somewhere else. Nothing downstream can
 * tell the difference.
 *
 * <p>So two things that <em>can</em> be checked are, and neither is disclosed to the model. The
 * grid's size catches a grid counted wrong from the start, which is the likeliest way an off-by-one
 * happens. Agreement between two readings catches a position counted wrong inside a grid counted
 * right, which the size cannot see.
 *
 * <p>Agreement rather than a known landmark, because this image has none: its one visually distinct
 * feature is the target itself, boosted in colour on purpose to make it findable. There is no
 * second position whose answer is already known, so a calibration against one cannot be built.
 *
 * <p>This is deliberately not proof. Two readings from one model can be wrong the same way, and
 * nothing here sees it when they are. It catches an unsteady reading, not a consistently biased
 * one.
 *
 * <p>Not a tool the model calls. There is one image and one moment to read it, so a tool would only
 * add decisions the model can get wrong — whether to call it, when, how often — and reading first
 * lets a disagreement end the run before any planning tokens are spent.
 */
final class GridReader {

    private static final Logger log = LoggerFactory.getLogger(GridReader.class);

    /**
     * What one reading of the image says.
     *
     * <p>Named fields rather than a pair: an ordered pair crossing this boundary is one a later
     * reader can put back together the wrong way round, and nothing downstream would know.
     *
     * <p>Component order is schema order, and schema order is the order a strict structured answer
     * is generated in. {@code reasoning} comes first for that reason alone: written after the
     * numbers it could only justify them, where written before it is working the numbers come out
     * of. Nothing reads it — it is here to be produced, and to explain a rejected reading in the
     * transcript afterwards.
     *
     * @param reasoning the model's own account of how it counted, before it counts
     */
    record Reading(
            String reasoning,
            int gridColumns,
            int gridRows,
            int targetColumn,
            int targetRow) {}

    /**
     * One delegated look at the image.
     *
     * <p>A seam of its own because {@code sendStructured} is declared on {@code LlmClient} and not
     * on {@code ChatTransport}: the library interface carries only the tool-calling round trip, so
     * a structured call cannot be faked through it. The runner supplies the real call; a test
     * supplies a lambda draining a queue.
     */
    @FunctionalInterface
    interface Read {
        Reading read();
    }

    private final TaskParams.Grid grid;
    private final Read read;

    GridReader(TaskParams.Grid grid, Read read) {
        this.grid = grid;
        this.read = read;
    }

    /**
     * @return the first reading that agrees with both checks
     * @throws IllegalStateException once every attempt has disagreed — the run ends here rather
     *                               than submitting a sequence built on a number nothing confirms
     */
    Reading read() {
        String disagreement = null;
        int readings = 0;

        for (int attempt = 1; attempt <= grid.readAttempts(); attempt++) {
            Reading first = read.read();
            readings++;

            // a first reading that already disagrees with the known grid is doomed whatever the
            // second says, and this is a loop that spends per turn: the attempt ends here
            disagreement = miscountedGrid(first);
            if (disagreement != null) {
                log.info("reading rejected on attempt {}: {}", attempt, disagreement);
                continue;
            }

            Reading second = read.read();
            readings++;
            disagreement = disagreementIn(first, second);

            if (disagreement == null) {
                log.info("reading accepted on attempt {}: target column {} row {}",
                        attempt, first.targetColumn(), first.targetRow());
                return first;
            }
            log.info("reading rejected on attempt {}: {}", attempt, disagreement);
        }

        // the readings are counted rather than derived from the attempts: a short-circuited attempt
        // costs one reading, not two, and a diagnostic that overstates what was spent is the small
        // end of keeping a second count beside the one that actually knows
        throw new IllegalStateException(
                "No pair of readings agreed, after " + grid.readAttempts() + " attempts and "
                        + readings + " readings. The last disagreed because " + disagreement
                        + ". Nothing was submitted: a coordinate nothing confirms would render a"
                        + " well-formed sequence that acts in the wrong place.");
    }

    /**
     * The second reading against the grid, then the two against each other.
     *
     * <p>The first has already been checked by the caller, which ends the attempt when it fails, so
     * checking it again here could never fire.
     *
     * @return what disagreed, or null where nothing did
     */
    private String disagreementIn(Reading first, Reading second) {
        String counted = miscountedGrid(second);
        if (counted != null) {
            return counted;
        }

        // What the grid size cannot see. Both readings counted the grid right and put the target in
        // different cells of it, so at least one of them is wrong and nothing says which.
        // Transposing a column and a row looks exactly like this.
        if (first.targetColumn() != second.targetColumn() || first.targetRow() != second.targetRow()) {
            return "two readings put the target in different sectors — column " + first.targetColumn()
                    + " row " + first.targetRow() + ", then column " + second.targetColumn() + " row "
                    + second.targetRow();
        }
        return null;
    }

    private String miscountedGrid(Reading reading) {
        if (reading == null) {
            return "nothing came back from a reading";
        }
        if (reading.gridColumns() != grid.columns() || reading.gridRows() != grid.rows()) {
            return "a reading counted a grid of " + reading.gridColumns() + " columns by "
                    + reading.gridRows() + " rows, where the grid is " + grid.columns() + " by "
                    + grid.rows();
        }
        return null;
    }
}
