package io.github.dbonkowska.dscribe.labs.s02e03;

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
 * Keys whose mistakes produce a working run that cannot succeed.
 *
 * <p>This lesson hands the file two things no earlier one did: the shape of a source line, and the
 * shape of a submitted one. Both fail far from where they are written. A line pattern missing a
 * group throws on the first line of a downloaded file — after the transcript has opened and the
 * download has been recorded — and a line format with a {@code %s} too few loses a field silently,
 * because {@code String.formatted} ignores arguments it has nowhere to put. The submission then
 * goes out without its severity, and the hub's rejection reads as the model choosing badly.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise. The pattern uses
 * character classes rather than backslash escapes so the text block reads as the file would.
 */
class TaskParamsTest {

    private static final String PATTERN =
            "(?<date>[0-9-]+) (?<time>[0-9:]+) (?<severity>[A-Z]+) (?<message>.*)";
    private static final String FORMAT = "%s %s %s %s";
    private static final TaskParams.ToolPrompt PROMPT = new TaskParams.ToolPrompt("n", "d");

    private static TaskParams with(String linePattern, String lineFormat, List<String> firstAttempt) {
        return new TaskParams(
                "x-task", "[{]F[}]", "a.txt", "text", linePattern, lineFormat, firstAttempt,
                new TaskParams.Budget(100, 5), PROMPT, PROMPT, PROMPT);
    }

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            dataFile=a.txt
            answerKey=text
            linePattern=%s
            lineFormat=%s
            firstAttempt.1=HIGH
            firstAttempt.2=MID
            budget.cap=100
            budget.margin=5
            zoom.name=look
            zoom.description=looks
            submit.name=send
            submit.description=sends
            answer.name=done
            answer.description=finishes
            """.formatted(PATTERN, FORMAT);

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals(List.of("HIGH", "MID"), params.firstAttempt(), "lists bind by index");
        assertEquals("text", params.answerKey());
        assertEquals(95, params.budget().tokens().effectiveCap());
        assertEquals("send", params.submit().name());
    }

    /**
     * A missing string key binds to null without complaint. Without {@code linePattern} the run
     * dies compiling null after the download; without {@code answerKey} or {@code verifyTask}
     * every attempt reaches the hub malformed and is rejected as though the selection were wrong;
     * without {@code flagPattern} the run dies after the result has already been earned.
     */
    @ParameterizedTest
    @ValueSource(strings = {"verifyTask", "flagPattern", "dataFile", "answerKey", "linePattern", "lineFormat"})
    void refusesARequiredKeyThatWasNeverWritten(String key) {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith(key + "="))
                .collect(Collectors.joining("\n"));

        // Jackson wraps anything a record constructor throws, so the type on this path is its
        // wrapper rather than ours. What matters to whoever reads the crash is unchanged: the
        // binding fails, and the message names the key to edit.
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    /** Present but empty is the same mistake as absent, and has to be refused the same way. */
    @ParameterizedTest
    @ValueSource(strings = {"verifyTask", "flagPattern", "dataFile", "answerKey", "linePattern", "lineFormat"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    @Test
    void refusesALinePatternMissingANamedGroup() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> with("(?<date>[0-9-]+) (?<time>[0-9:]+) (?<severity>[A-Z]+) .*", FORMAT, List.of("HIGH")));

        assertTrue(thrown.getMessage().contains("linePattern"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("message"),
                () -> "it has to name the group that is missing: " + thrown.getMessage());
    }

    @Test
    void refusesALinePatternThatDoesNotCompile() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> with("(?<date>[0-9-]+", FORMAT, List.of("HIGH")));

        assertTrue(thrown.getMessage().contains("linePattern"), thrown::getMessage);
    }

    /** The case {@code formatted} would let through: three slots, four values, one silently lost. */
    @Test
    void refusesALineFormatWithASlotTooFew() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> with(PATTERN, "%s %s %s", List.of("HIGH")));

        assertTrue(thrown.getMessage().contains("lineFormat"), thrown::getMessage);
    }

    /** An empty selection would submit nothing, and be rejected for it, before the loop exists. */
    @Test
    void refusesAFirstAttemptThatSelectsNothing() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> with(PATTERN, FORMAT, List.of()));

        assertTrue(thrown.getMessage().contains("firstAttempt"), thrown::getMessage);
        assertThrows(IllegalStateException.class, () -> with(PATTERN, FORMAT, null));
    }

    /** A primitive binds a missing key to zero, and every submission is then too long for it. */
    @Test
    void refusesACapThatWasNeverSetInTheFile() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("budget.cap="))
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("budget.cap"),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    @Test
    void refusesAMarginThatWouldLeaveNothingSendable() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new TaskParams.Budget(40, 40));

        assertTrue(thrown.getMessage().contains("budget.margin"), thrown::getMessage);
    }
}
