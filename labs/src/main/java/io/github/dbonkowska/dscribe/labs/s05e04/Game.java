package io.github.dbonkowska.dscribe.labs.s05e04;

import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One game, start to result: for every column, a positively clear status, a labelled hint, one move.
 *
 * <p>Code owns the loop and every decision in it. The model only labels a hint, and a label can
 * reach a move only through {@link Steering}. Position always comes from the latest reply, never a
 * local count: a retried move can step twice, and a count would then disagree with the hub
 * silently. Anything that cannot be positively read abandons the game rather than guessing, because
 * a new game costs a few calls and a wrong move costs this one anyway.
 */
final class Game {

    /** Reads per column. Re-reads return the same text, so this bounds a dead end, not bad luck. */
    static final int MAX_SCANS = 3;

    /** How many columns before the last the run stops holding the middle and heads for the target. */
    static final int HOMING_COLUMNS = 3;

    /** One label, and one more of the same text if it was unclear. */
    private static final int MAX_LABELS = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * What a game does to the outside world, narrow so the gates can be driven from a script.
     *
     * <p>Every call but {@link #label} is a hub call, and a spent retry budget is thrown rather than
     * returned: that ends the run, not just the game.
     */
    interface Field {

        HubResponse start();

        HubResponse scan();

        /** Returns nothing on purpose: only a later clear reading opens the gate. */
        void disarm(Reading.Targeted refusal);

        HubResponse hint();

        Bearing label(String hintText);

        HubResponse move(String command);

        void note(String note);
    }

    sealed interface Outcome {

        record Flagged(String flag) implements Outcome {}

        record Abandoned(String reason) implements Outcome {}
    }

    private record Position(int row, int column, int obstacleRow) {}

    private final Field field;
    private final TaskParams task;
    private final Reading.Decoder decoder;
    private final Steering steering;
    private final Pattern flag;

    Game(Field field, TaskParams task) {
        this.field = field;
        this.task = task;
        this.decoder = new Reading.Decoder(
                task.clearMarker(), task.frequencyKey(), task.codeKey(), task.confusables());
        this.steering = new Steering(task.rows(), task.ahead(), task.left(), task.right());
        this.flag = Pattern.compile(task.flagPattern());
    }

    Outcome play() {
        JsonNode opening = parsed(field.start());
        Optional<Position> opened = position(opening);
        OptionalInt targetRow = number(opening, task.replies().targetRow());
        if (opened.isEmpty() || targetRow.isEmpty()) {
            return new Outcome.Abandoned("the opening reply has no position");
        }

        Position here = opened.get();
        // bounded by the grid rather than by the hub's column, which is only as good as its replies
        for (int step = 0; step < task.columns(); step++) {
            if (here.column() >= task.columns()) {
                return new Outcome.Abandoned("reached the last column without a result");
            }

            Optional<String> closed = closedGate(here);
            if (closed.isPresent()) {
                return new Outcome.Abandoned(closed.get());
            }

            Bearing bearing = bearing(here);
            Optional<String> move =
                    steering.move(here.row(), here.obstacleRow(), bearing, target(here, targetRow.getAsInt()));
            if (move.isEmpty()) {
                return new Outcome.Abandoned(
                        "no safe move in column " + here.column() + " for bearing " + bearing.label());
            }

            HubResponse reply = field.move(move.get());
            Matcher result = flag.matcher(reply.body());
            if (result.find()) {
                return new Outcome.Flagged(result.group());
            }

            JsonNode state = parsed(reply);
            if (reply.status() != 200 || state.at(task.replies().ended()).asBoolean(false)) {
                return new Outcome.Abandoned(
                        "the game was lost moving " + move.get() + " from column " + here.column());
            }
            Optional<Position> next = position(state);
            if (next.isEmpty()) {
                return new Outcome.Abandoned("the reply to a move has no position");
            }
            if (next.get().column() != here.column() + 1) {
                field.note("Expected column " + (here.column() + 1) + ", the reply says " + next.get().column()
                        + ". A retried move may have stepped twice, past a column nothing checked.");
            }
            here = next.get();
        }
        return new Outcome.Abandoned("ran out of steps without a result");
    }

    /** The reason the gate stayed closed, or empty once a reading was positively clear. */
    private Optional<String> closedGate(Position here) {
        for (int scan = 0; scan < MAX_SCANS; scan++) {
            HubResponse reply = field.scan();
            if (reply.status() != 200) {
                return Optional.of("the status read was refused in column " + here.column());
            }
            switch (decoder.read(reply.body())) {
                case Reading.Clear clear -> {
                    return Optional.empty();
                }
                case Reading.Targeted refusal -> field.disarm(refusal);
                case Reading.Unreadable unreadable -> {
                    // read again up to the cap, for a reply that may have been cut short
                }
            }
        }
        return Optional.of("no clear reading in column " + here.column() + " after " + MAX_SCANS + " reads");
    }

    /** What the hint says, or {@link Bearing#UNCLEAR}, which no move can come from. */
    private Bearing bearing(Position here) {
        JsonNode text = parsed(field.hint()).at(task.replies().hint());
        if (!text.isString() || text.asString().isBlank()) {
            return Bearing.UNCLEAR;
        }
        for (int attempt = 0; attempt < MAX_LABELS; attempt++) {
            Bearing bearing = field.label(text.asString());
            if (bearing != Bearing.UNCLEAR && !steering.pointsOffGrid(here.row(), bearing)) {
                return bearing;
            }
        }
        return Bearing.UNCLEAR;
    }

    /** The middle cannot dead-end and the edges can, so the run holds it for as long as it may. */
    private int target(Position here, int targetRow) {
        return here.column() >= task.columns() - HOMING_COLUMNS ? targetRow : (task.rows() + 1) / 2;
    }

    private Optional<Position> position(JsonNode state) {
        OptionalInt row = number(state, task.replies().row());
        OptionalInt column = number(state, task.replies().column());
        OptionalInt obstacle = number(state, task.replies().obstacleRow());
        if (row.isEmpty() || column.isEmpty() || obstacle.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Position(row.getAsInt(), column.getAsInt(), obstacle.getAsInt()));
    }

    private static OptionalInt number(JsonNode state, String pointer) {
        JsonNode value = state.at(pointer);
        return value.isIntegralNumber() ? OptionalInt.of(value.intValue()) : OptionalInt.empty();
    }

    /** A reply that is not JSON reads as one with nothing in it, which every caller already handles. */
    private static JsonNode parsed(HubResponse reply) {
        try {
            return MAPPER.readTree(reply.body());
        } catch (JacksonException e) {
            return MissingNode.getInstance();
        }
    }
}
