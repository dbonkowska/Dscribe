package io.github.dbonkowska.dscribe.labs.s02e02;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose absence produces a working run that cannot succeed.
 *
 * <p>A primitive binds a missing key to zero, a list or a string to null — the trap the
 * principles name under "blank configuration means absent, not configured". None of them crash
 * where the mistake is. {@code maxMoves} of zero refuses every move as a tool result, so the model
 * reads a budget it has already spent. Empty {@code positions} produce a schema {@code enum} no
 * value satisfies. A missing string key fails later still — see the parameterized cases. All of
 * them spend the run and read as the model's fault.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    private static final TaskParams.ToolPrompt PROMPT = new TaskParams.ToolPrompt("n", "d");

    private static TaskParams with(List<String> positions, int maxMoves) {
        return withTarget(positions, maxMoves, null);
    }

    private static TaskParams withTarget(List<String> positions, int maxMoves, String targetImage) {
        return new TaskParams(
                "x-task", "\\{FLG:.+}", "a.png", "image/png", targetImage, "reset=1",
                positions, maxMoves, "place", PROMPT, PROMPT, PROMPT);
    }

    @Test
    void refusesAMoveBudgetThatWasNeverSetInTheFile() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("maxMoves="))
                .collect(Collectors.joining("\n"));

        // Jackson wraps anything a record constructor throws, so the type on this path is its
        // wrapper rather than ours. What matters to whoever reads the crash is unchanged: the
        // binding fails, and the message names the key to edit.
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("maxMoves"),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    @Test
    void refusesAnEmptyVocabularyRatherThanOfferingAnUncallableTool() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> with(List.of(), 9));

        assertTrue(thrown.getMessage().contains("positions"), thrown::getMessage);
    }

    @Test
    void refusesAVocabularyThatWasNeverSetAtAll() {
        assertThrows(IllegalStateException.class, () -> with(null, 9));
    }

    @Test
    void acceptsABudgetAndAVocabularyThatAreActuallyThere() {
        TaskParams params = with(List.of("a1", "a2"), 9);

        assertEquals(List.of("a1", "a2"), params.positions());
        assertEquals(9, params.maxMoves());
    }

    /**
     * The target reaches the model one of two ways — shown as an image, or described in the
     * delegated prompt — and an empty key has to mean the second rather than "show the image at
     * the empty URL", which is a request the provider refuses at the first call.
     */
    @Test
    void treatsABlankTargetImageAsNoneAtAll() {
        assertNull(withTarget(List.of("a1"), 9, "").targetImage());
        assertNull(withTarget(List.of("a1"), 9, "   ").targetImage());
        assertNull(withTarget(List.of("a1"), 9, null).targetImage());
    }

    @Test
    void keepsATargetImageThatIsActuallySet() {
        assertEquals("https://e/target.png",
                withTarget(List.of("a1"), 9, "https://e/target.png").targetImage());
    }

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=x
            dataFile=a.png
            mediaType=image/png
            resetQuery=reset=1
            positions.1=a1
            maxMoves=9
            moveKey=place
            vision.name=look
            vision.description=looks
            move.name=move
            move.description=moves
            answer.name=done
            answer.description=finishes
            """;

    /**
     * A missing string key binds to null without complaint, and each of these fails somewhere
     * worse than here. Without {@code resetQuery} the startup reset asks for {@code ?null}, gets
     * the current state back with a 200, and the run starts from a previous run's leftovers with
     * nothing in its own record saying so. Without {@code moveKey} or {@code verifyTask} every
     * move throws after it has been counted, so the budget drains while nothing is sent — and the
     * model reads each "Tool failed" as worth another try. Without {@code flagPattern} the run
     * dies after the result has already been earned.
     */
    @ParameterizedTest
    @ValueSource(strings = {"verifyTask", "flagPattern", "dataFile", "mediaType", "resetQuery", "moveKey"})
    void refusesARequiredKeyThatWasNeverWritten(String key) {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith(key + "="))
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    /** Present but empty is the same mistake as absent, and has to be refused the same way. */
    @ParameterizedTest
    @ValueSource(strings = {"verifyTask", "flagPattern", "dataFile", "mediaType", "resetQuery", "moveKey"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    @Test
    void bindsACompleteFile() {
        assertEquals("place", new JavaPropsMapper().readValue(COMPLETE, TaskParams.class).moveKey());
    }

    @Test
    void bindsTheAddressesByIndexFromTheFile() {
        String props = """
                verifyTask=x-task
                flagPattern=x
                dataFile=a.png
                mediaType=image/png
                resetQuery=reset=1
                positions.1=a1
                positions.2=a2
                maxMoves=9
                moveKey=place
                vision.name=look
                vision.description=looks
                move.name=move
                move.description=moves
                answer.name=done
                answer.description=finishes
                """;

        TaskParams params = new JavaPropsMapper().readValue(props, TaskParams.class);

        assertEquals(List.of("a1", "a2"), params.positions());
        assertEquals("place", params.moveKey());
        assertEquals("look", params.vision().name());
    }
}
