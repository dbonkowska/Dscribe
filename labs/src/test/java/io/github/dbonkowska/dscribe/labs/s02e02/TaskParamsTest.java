package io.github.dbonkowska.dscribe.labs.s02e02;

import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two keys whose absence produces a working run that cannot succeed.
 *
 * <p>A primitive binds a missing key to zero and a list binds to null — the trap the principles
 * name under "blank configuration means absent, not configured". Neither crashes. {@code
 * maxMoves} of zero refuses every move as a tool result, so the model reads a budget it has
 * already spent and reports failure on a run that sent nothing. Empty {@code positions} produce
 * a schema {@code enum} no value satisfies, so the tool is offered and can never legally be
 * called. Both spend model iterations and both read as the model's fault.
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
        String props = """
                verifyTask=x-task
                positions.1=a1
                """;

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
