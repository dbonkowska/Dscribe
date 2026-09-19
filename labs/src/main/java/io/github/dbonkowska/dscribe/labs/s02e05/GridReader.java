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
 * totals catch a grid counted wrong from the start, which is the likeliest way an off-by-one
 * happens. The anchor — a landmark whose position is already known — catches a position counted
 * wrong inside a grid counted right, which the totals cannot see. A reading that misses the
 * landmark we can check has said nothing worth acting on about the one we cannot.
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
     * @param reasoning the model's own account of how it counted, kept for the transcript — a
     *                  rejected reading is the one worth reading afterwards
     */
    record Reading(
            int gridColumns,
            int gridRows,
            int anchorColumn,
            int anchorRow,
            int targetColumn,
            int targetRow,
            String reasoning) {}

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

        for (int attempt = 1; attempt <= grid.readAttempts(); attempt++) {
            Reading reading = read.read();
            disagreement = disagreementWith(reading);

            if (disagreement == null) {
                log.info("reading accepted on attempt {}: target column {} row {}",
                        attempt, reading.targetColumn(), reading.targetRow());
                return reading;
            }
            log.info("reading rejected on attempt {}: {}", attempt, disagreement);
        }

        throw new IllegalStateException(
                "No reading of the image agreed with what is already known about it, after "
                        + grid.readAttempts() + " attempts. The last disagreed because " + disagreement
                        + ". Nothing was submitted: a coordinate nothing confirms would render a"
                        + " well-formed sequence that acts in the wrong place.");
    }

    /** @return what disagreed, or null where nothing did */
    private String disagreementWith(Reading reading) {
        if (reading == null) {
            return "nothing came back from the reading";
        }
        if (reading.gridColumns() != grid.columns() || reading.gridRows() != grid.rows()) {
            return "it counted a grid of " + reading.gridColumns() + " columns by "
                    + reading.gridRows() + " rows, where the grid is " + grid.columns() + " by "
                    + grid.rows();
        }
        if (reading.anchorColumn() != grid.anchorColumn() || reading.anchorRow() != grid.anchorRow()) {
            return "it placed the anchor at column " + reading.anchorColumn() + " row "
                    + reading.anchorRow() + ", where the anchor is at column " + grid.anchorColumn()
                    + " row " + grid.anchorRow();
        }
        return null;
    }
}
