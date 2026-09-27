package io.github.dbonkowska.dscribe.labs.s04e03;

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
 * Keys whose mistakes produce a run that cannot succeed. A string key nobody wrote binds to null
 * rather than failing, and would fail later and further from the file: a missing task name on the
 * first action, a missing flag pattern after the result was already earned.
 *
 * <p>The reset keys matter more than most: the reset wipes everything the model has learned about
 * the environment, so the one the run sends has to be one the model is refused.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            action.name=act
            action.description=does one action
            resetAction=wipe
            refusedActions.1=wipe
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals("x-task", params.verifyTask());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals("act", params.action().name());
        assertEquals("does one action", params.action().description());
        assertEquals("wipe", params.resetAction());
        assertEquals(List.of("wipe"), params.refusedActions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"verifyTask", "flagPattern", "action.name", "action.description", "resetAction"})
    void refusesARequiredKeyThatWasNeverWritten(String key) {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith(key + "="))
                .collect(Collectors.joining("\n"));

        // Jackson wraps anything a record constructor throws, so the type on this path is its
        // wrapper rather than ours. The binding fails and the message names the key to edit.
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    /** Present but empty is the same mistake as absent, and has to be refused the same way. */
    @ParameterizedTest
    @ValueSource(strings = {"verifyTask", "flagPattern", "action.name", "action.description", "resetAction"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /**
     * A pattern that does not compile throws on the first reply it is applied to, after the run has
     * already spent and the hub has printed the result it was meant to find.
     */
    @Test
    void refusesAFlagPatternThatDoesNotCompile() {
        String props = COMPLETE.replace("flagPattern=[{]F[}]", "flagPattern=[a-z");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("flagPattern"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("backslash"),
                () -> "it has to say how properties files mangle patterns: " + thrown.getMessage());
    }

    /** The list exists to keep the reset away from the model; an empty one is a bundle that forgot it. */
    @Test
    void refusesAFileWithNoRefusedActions() {
        String props = COMPLETE.replace("refusedActions.1=wipe\n", "");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("refusedActions"), thrown::getMessage);
    }

    @Test
    void refusesABlankRefusedAction() {
        String props = COMPLETE.replace("refusedActions.1=wipe", "refusedActions.1=wipe\nrefusedActions.2=   ");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("refusedActions"), thrown::getMessage);
    }

    /** Compared and stored stripped: a padded entry would otherwise refuse nothing the model sends. */
    @Test
    void storesARefusedActionStripped() {
        String props = COMPLETE.replace("refusedActions.1=wipe", "refusedActions.1= wipe ");

        TaskParams params = new JavaPropsMapper().readValue(props, TaskParams.class);

        assertEquals(List.of("wipe"), params.refusedActions());
    }

    /**
     * The run's own reset has to be one the model is refused. Otherwise the model could send the
     * same wipe under the name the run uses, which the list never mentions.
     */
    @Test
    void refusesAResetActionTheModelIsNotRefused() {
        String props = COMPLETE.replace("resetAction=wipe", "resetAction=clear");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("resetAction") && thrown.getMessage().contains("refusedActions"),
                () -> "it has to name both keys, since either may be the one to fix: " + thrown.getMessage());
    }
}
