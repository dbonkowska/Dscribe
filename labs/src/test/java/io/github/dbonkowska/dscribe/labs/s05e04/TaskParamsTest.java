package io.github.dbonkowska.dscribe.labs.s05e04;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose mistakes surface only after a run has started, where the cost is a lost game. A digest
 * name nobody can compute would fail on the first refused step, and a pointer the reply cannot
 * resolve would read as a missing value rather than a typo.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value: each test below takes one of them away or spoils it. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            statusPath=/api/x
            hintPath=/api/y
            commandKey=op
            start=begin
            ahead=on
            left=port
            right=star
            rows=5
            columns=9
            clearMarker=all fine
            numberKey=tune
            codeKey=token
            hashKey=proof
            digestAlgorithm=SHA-1
            digestSuffix=-x
            confusables.1=mn
            confusables.2=1l
            replies.row=/at/row
            replies.column=/at/col
            replies.targetRow=/end/row
            replies.obstacleRow=/here/row
            replies.ended=/ended
            replies.hint=/note
            """;

    private static TaskParams bind(String props) {
        return new JavaPropsMapper().readValue(props, TaskParams.class);
    }

    private static String refusal(String props) {
        return assertThrows(RuntimeException.class, () -> bind(props)).getMessage();
    }

    @Test
    void bindsACompleteFile() {
        TaskParams params = bind(COMPLETE);

        assertEquals("x-task", params.verifyTask());
        assertEquals("/api/x", params.statusPath());
        assertEquals("/api/y", params.hintPath());
        assertEquals("op", params.commandKey());
        assertEquals(5, params.rows());
        assertEquals(9, params.columns());
        assertEquals("SHA-1", params.digestAlgorithm());
        assertEquals(List.of("mn", "1l"), params.confusables());
        assertEquals(
                new TaskParams.Replies("/at/row", "/at/col", "/end/row", "/here/row", "/ended", "/note"),
                params.replies());
    }

    /** Appended to the hub's base URL with the key attached, so the value decides where the key goes. */
    @ParameterizedTest
    @ValueSource(strings = {"api/x", "@elsewhere/x", "//elsewhere/x", "https://elsewhere/x", "/a b"})
    void refusesAPathThatIsNotPlain(String path) {
        String status = refusal(COMPLETE.replace("statusPath=/api/x", "statusPath=" + path));
        String hint = refusal(COMPLETE.replace("hintPath=/api/y", "hintPath=" + path));

        assertTrue(status.contains("statusPath") && status.contains(path), status);
        assertTrue(hint.contains("hintPath") && hint.contains(path), hint);
    }

    @Test
    void refusesAMissingRepliesBlock() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("replies."))
                .collect(Collectors.joining(System.lineSeparator()));

        assertTrue(refusal(props).contains("replies"), () -> refusal(props));
    }

    /** Named at startup, not at the first refused step, where a typo would cost the game in play. */
    @Test
    void refusesADigestNobodyCanCompute() {
        String props = COMPLETE.replace("digestAlgorithm=SHA-1", "digestAlgorithm=NOPE-9");

        String message = refusal(props);
        assertTrue(message.contains("digestAlgorithm") && message.contains("NOPE-9"), message);
    }

    @Test
    void refusesAFlagPatternThatDoesNotCompile() {
        String props = COMPLETE.replace("flagPattern=[{]F[}]", "flagPattern=[");

        assertTrue(refusal(props).contains("flagPattern"), () -> refusal(props));
    }

    /** {@code JsonNode.at} rejects it mid-game otherwise, with nothing naming the key. */
    @Test
    void refusesAReplyFieldThatIsNotAPointer() {
        String props = COMPLETE.replace("replies.row=/at/row", "replies.row=at.row");

        String message = refusal(props);
        assertTrue(message.contains("replies.row") && message.contains("at.row"), message);
    }

    /** One letter or three cannot be swapped for each other, so the entry would decode nothing. */
    @ParameterizedTest
    @ValueSource(strings = {"m", "mno"})
    void refusesAConfusableThatIsNotAPair(String entry) {
        String props = COMPLETE.replace("confusables.1=mn", "confusables.1=" + entry);

        String message = refusal(props);
        assertTrue(message.contains("confusables") && message.contains(entry), message);
    }

    /** Absent must not mean zero: a grid of no rows has no safe move, and every game would stop. */
    @Test
    void refusesAMissingGridSizeRatherThanReadingItAsZero() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("rows"))
                .collect(Collectors.joining("\n"));

        assertTrue(refusal(props).contains("rows"), () -> refusal(props));
    }

    @Test
    void refusesAGridOfNoColumns() {
        String props = COMPLETE.replace("columns=9", "columns=0");

        assertTrue(refusal(props).contains("columns"), () -> refusal(props));
    }

    @ParameterizedTest
    @ValueSource(strings = {"verifyTask=x-task", "statusPath=/api/x", "hintPath=/api/y", "commandKey=op",
            "start=begin", "ahead=on", "left=port", "right=star", "clearMarker=all fine", "numberKey=tune",
            "codeKey=token", "hashKey=proof", "digestSuffix=-x", "replies.hint=/note"})
    void refusesABlankKey(String line) {
        String key = line.substring(0, line.indexOf('='));
        String props = COMPLETE.replace(line, key + "=  ");

        assertTrue(refusal(props).contains(key), () -> refusal(props));
    }
}
