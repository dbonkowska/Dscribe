package io.github.dbonkowska.dscribe.labs.s05e04;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gates, because a wrong move cannot be taken back and a lost game says little about why. The
 * hub is not a substitute here: it answers a mistake by ending the game.
 *
 * <p>Driven by a scripted field that records the order of its calls, so "nothing moved before the
 * reply was clear" is asserted as a list rather than inferred from the outcome.
 *
 * <p>Keys, commands and replies are invented: nothing in this file is supplied by the exercise.
 */
class GameTest {

    private static final int ROWS = 5;
    private static final int COLUMNS = 6;
    private static final int MIDDLE = 3;
    private static final String LEFT = "l";
    private static final String AHEAD = "a";
    private static final String RIGHT = "r";

    private static final TaskParams TASK = new TaskParams(
            "x-task", "[{]F[}]", "/api/x", "/api/y", "op", "begin", AHEAD, LEFT, RIGHT, ROWS, COLUMNS,
            "all fine", "tune", "token", "proof", "SHA-1", "-x", List.of("mn", "o0"),
            new TaskParams.Replies("/at/row", "/at/col", "/end/row", "/here/row", "/ended", "/note"));

    private static final String CLEAR = "\"all fine\"";
    private static final String REFUSED = "{\"tune\": 7, \"token\": \"abc\"}";
    private static final String GARBAGE = "?!";
    private static final Reading.Targeted DECODED = new Reading.Targeted(7, "abc");

    @Test
    void movesOnlyAfterAPositivelyClearReply() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR)
                .labels(Bearing.AHEAD)
                .moves(lost());

        new Game(field, TASK).play();

        assertEquals(List.of("start", "scan", "hint", "label", "move " + LEFT), field.calls);
    }

    @Test
    void clearsARefusedStepAndChecksAgainBeforeMoving() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(REFUSED, CLEAR)
                .labels(Bearing.AHEAD)
                .moves(lost());

        new Game(field, TASK).play();

        assertEquals(
                List.of("start", "scan", "disarm " + DECODED, "scan", "hint", "label", "move " + LEFT),
                field.calls);
    }

    /** Clearing is believed only when a later reply says so, whatever the clearing call returned. */
    @Test
    void aRefusalThatPersistsNeverReachesAMove() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2)).scans(REFUSED, REFUSED, REFUSED);

        Game.Outcome outcome = new Game(field, TASK).play();

        assertInstanceOf(Game.Outcome.Abandoned.class, outcome);
        assertEquals(Game.MAX_SCANS, field.count("scan"));
        assertEquals(0, field.count("move"));
    }

    @Test
    void anUnreadableReplyPastTheCapAbandonsTheGame() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2)).scans(GARBAGE, GARBAGE, GARBAGE);

        Game.Outcome outcome = new Game(field, TASK).play();

        assertInstanceOf(Game.Outcome.Abandoned.class, outcome);
        assertEquals(Game.MAX_SCANS, field.count("scan"));
        assertEquals(0, field.count("hint"));
    }

    @Test
    void anUnreadableReplyWithinTheCapIsReadAgain() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(GARBAGE, CLEAR)
                .labels(Bearing.AHEAD)
                .moves(lost());

        new Game(field, TASK).play();

        assertEquals(2, field.count("scan"));
        assertEquals(1, field.count("move"));
    }

    /** A refused read happens only once the game is already over, so reading again spends nothing useful. */
    @Test
    void aRefusedReadAbandonsAtOnce() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2)).scanReplies(reply(400, "{\"code\":-1}"));

        Game.Outcome outcome = new Game(field, TASK).play();

        assertInstanceOf(Game.Outcome.Abandoned.class, outcome);
        assertEquals(1, field.count("scan"));
    }

    @Test
    void anUnclearLabelIsAskedOnceMoreOfTheSameHint() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR)
                .labels(Bearing.UNCLEAR, Bearing.AHEAD)
                .moves(lost());

        new Game(field, TASK).play();

        assertEquals(1, field.count("hint"), "repeat reads return the same text");
        assertEquals(2, field.count("label"));
        assertEquals(1, field.count("move"));
    }

    @Test
    void anUnclearLabelTwiceAbandonsTheGame() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR)
                .labels(Bearing.UNCLEAR, Bearing.UNCLEAR);

        Game.Outcome outcome = new Game(field, TASK).play();

        assertInstanceOf(Game.Outcome.Abandoned.class, outcome);
        assertEquals(0, field.count("move"));
    }

    /** Nothing lies past the edge, so this label is a misread and is treated like one. */
    @Test
    void aLabelPointingOffTheGridCountsAsUnclear() {
        Script field = new Script(opening(1, 1, 3, 2))
                .scans(CLEAR)
                .labels(Bearing.LEFT, Bearing.LEFT);

        Game.Outcome outcome = new Game(field, TASK).play();

        assertInstanceOf(Game.Outcome.Abandoned.class, outcome);
        assertEquals(2, field.count("label"));
        assertEquals(0, field.count("move"));
    }

    @Test
    void aHintWithNoTextAbandonsWithoutAskingTheModel() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR)
                .hintReply(reply(200, "{}"));

        Game.Outcome outcome = new Game(field, TASK).play();

        assertInstanceOf(Game.Outcome.Abandoned.class, outcome);
        assertEquals(0, field.count("label"));
    }

    @Test
    void noSafeMoveAbandonsTheGame() {
        Script field = new Script(opening(1, 1, 2, 2))
                .scans(CLEAR)
                .labels(Bearing.AHEAD);

        Game.Outcome outcome = new Game(field, TASK).play();

        assertInstanceOf(Game.Outcome.Abandoned.class, outcome);
        assertEquals(0, field.count("move"));
    }

    @Test
    void aRefusedMoveAbandonsTheGame() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR)
                .labels(Bearing.AHEAD)
                .moves(lost());

        assertInstanceOf(Game.Outcome.Abandoned.class, new Game(field, TASK).play());
        assertEquals(1, field.count("move"));
    }

    @Test
    void aReplySayingTheGameEndedAbandonsItWhateverItsStatus() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR)
                .labels(Bearing.AHEAD)
                .moves(reply(200, "{\"ended\": true, \"at\": {\"row\": 2, \"col\": 2}}"));

        assertInstanceOf(Game.Outcome.Abandoned.class, new Game(field, TASK).play());
        assertEquals(1, field.count("move"));
    }

    /** From the middle until the last stretch, where the hub-reported target takes over. */
    @Test
    void headsForTheMiddleThenTheTargetThreeColumnsFromTheEnd() {
        int target = ROWS;
        int lastBeforeSwitch = COLUMNS - Game.HOMING_COLUMNS - 1;
        Script field = new Script(opening(MIDDLE, lastBeforeSwitch, 1, target))
                .scans(CLEAR, CLEAR)
                .labels(Bearing.LEFT, Bearing.LEFT)
                .moves(state(MIDDLE, lastBeforeSwitch + 1, 1), lost());

        new Game(field, TASK).play();

        assertEquals(List.of(AHEAD, RIGHT), field.moves());
    }

    @Test
    void aFlagInAMoveReplyEndsTheGame() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR)
                .labels(Bearing.AHEAD)
                .moves(reply(200, "{\"message\": \"done {F}\"}"));

        assertEquals(new Game.Outcome.Flagged("{F}"), new Game(field, TASK).play());
    }

    /** The result may arrive under any status, so it is looked for before the status is. */
    @Test
    void aFlagWinsOverARefusalStatus() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR)
                .labels(Bearing.AHEAD)
                .moves(reply(400, "{\"message\": \"{F}\"}"));

        assertEquals(new Game.Outcome.Flagged("{F}"), new Game(field, TASK).play());
    }

    /** A retried move may have stepped twice, past a column nothing checked, so it must be visible. */
    @Test
    void notesAColumnOtherThanTheExpectedOneAndTrustsTheReply() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR, CLEAR)
                .labels(Bearing.AHEAD, Bearing.AHEAD)
                .moves(state(MIDDLE, 3, 1), lost());

        new Game(field, TASK).play();

        assertEquals(1, field.notes.size(), () -> field.notes.toString());
        assertTrue(field.notes.getFirst().contains("3"), () -> field.notes.toString());
        assertEquals(2, field.count("move"), "the game goes on from where the hub says it is");
    }

    @Test
    void notesNothingOnAnOrdinaryStep() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR, CLEAR)
                .labels(Bearing.AHEAD, Bearing.AHEAD)
                .moves(state(MIDDLE, 2, 1), lost());

        new Game(field, TASK).play();

        assertEquals(List.of(), field.notes);
    }

    @Test
    void abandonsAnOpeningWithNoPosition() {
        Script field = new Script(reply(200, "not json"));

        assertInstanceOf(Game.Outcome.Abandoned.class, new Game(field, TASK).play());
        assertEquals(List.of("start"), field.calls);
    }

    @Test
    void abandonsAMoveReplyWithNoPosition() {
        Script field = new Script(opening(MIDDLE, 1, 1, 2))
                .scans(CLEAR)
                .labels(Bearing.AHEAD)
                .moves(reply(200, "{}"));

        assertInstanceOf(Game.Outcome.Abandoned.class, new Game(field, TASK).play());
        assertEquals(1, field.count("move"));
    }

    /** No column lies past the last, so there is nothing to check and nothing to move to. */
    @Test
    void stopsAtTheLastColumnEvenWithoutAResult() {
        Script field = new Script(opening(MIDDLE, COLUMNS - 1, 1, MIDDLE))
                .scans(CLEAR)
                .labels(Bearing.LEFT)
                .moves(state(MIDDLE, COLUMNS, 1));

        assertInstanceOf(Game.Outcome.Abandoned.class, new Game(field, TASK).play());
        assertEquals(1, field.count("scan"));
        assertEquals(1, field.count("move"));
    }

    /** The hub reports the position, so a reply stuck on one column must not loop forever. */
    @Test
    void aColumnThatNeverAdvancesCannotLoopForever() {
        Script field = new Script(opening(MIDDLE, 1, 1, MIDDLE));
        for (int i = 0; i < COLUMNS + 1; i++) {
            field.scans(CLEAR).labels(Bearing.LEFT).moves(state(MIDDLE, 1, 1));
        }

        assertInstanceOf(Game.Outcome.Abandoned.class, new Game(field, TASK).play());
        assertFalse(field.count("move") > COLUMNS, "the run must stop on its own");
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static HubResponse reply(int status, String body) {
        return new HubResponse(status, TestHeaders.of(Map.of()), body);
    }

    private static HubResponse opening(int row, int column, int obstacle, int target) {
        return reply(200, "{\"at\": {\"row\": " + row + ", \"col\": " + column + "}, \"end\": {\"row\": " + target
                + "}, \"here\": {\"row\": " + obstacle + "}}");
    }

    private static HubResponse state(int row, int column, int obstacle) {
        return reply(200, "{\"at\": {\"row\": " + row + ", \"col\": " + column + "}, \"here\": {\"row\": "
                + obstacle + "}}");
    }

    private static HubResponse lost() {
        return reply(400, "{\"ended\": true}");
    }

    /** Answers each call from its own queue and keeps the order they came in. */
    private static final class Script implements Game.Field {

        private final HubResponse opening;
        private final Deque<HubResponse> scans = new ArrayDeque<>();
        private final Deque<HubResponse> moves = new ArrayDeque<>();
        private final Deque<Bearing> labels = new ArrayDeque<>();
        private HubResponse hint = reply(200, "{\"note\": \"some words\"}");
        private final List<String> calls = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        private Script(HubResponse opening) {
            this.opening = opening;
        }

        private Script scans(String... bodies) {
            Arrays.stream(bodies).forEach(body -> scans.add(reply(200, body)));
            return this;
        }

        private Script scanReplies(HubResponse... replies) {
            scans.addAll(List.of(replies));
            return this;
        }

        private Script labels(Bearing... bearings) {
            labels.addAll(List.of(bearings));
            return this;
        }

        private Script moves(HubResponse... replies) {
            moves.addAll(List.of(replies));
            return this;
        }

        private Script hintReply(HubResponse reply) {
            hint = reply;
            return this;
        }

        private long count(String call) {
            return calls.stream().filter(entry -> entry.startsWith(call)).count();
        }

        private List<String> moves() {
            return calls.stream()
                    .filter(entry -> entry.startsWith("move "))
                    .map(entry -> entry.substring("move ".length()))
                    .toList();
        }

        @Override
        public HubResponse start() {
            calls.add("start");
            return opening;
        }

        @Override
        public HubResponse scan() {
            calls.add("scan");
            return scans.removeFirst();
        }

        @Override
        public void disarm(Reading.Targeted refusal) {
            calls.add("disarm " + refusal);
        }

        @Override
        public HubResponse hint() {
            calls.add("hint");
            return hint;
        }

        @Override
        public Bearing label(String hintText) {
            calls.add("label");
            return labels.removeFirst();
        }

        @Override
        public HubResponse move(String command) {
            calls.add("move " + command);
            return moves.removeFirst();
        }

        @Override
        public void note(String note) {
            notes.add(note);
        }
    }
}
