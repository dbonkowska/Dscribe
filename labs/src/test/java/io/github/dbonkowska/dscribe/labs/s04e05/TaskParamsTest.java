package io.github.dbonkowska.dscribe.labs.s04e05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose mistakes produce a run that cannot succeed. A string key nobody wrote binds to null
 * rather than failing, and would fail later and further from the file.
 *
 * <p>The guard keys matter more than most. The reset tool and the orders delete are what the tool
 * refuses the model; a missing one leaves the model free to wipe its own progress or the orders
 * the run was told to leave alone. The list field is how the seeded orders are read at all.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            api.name=call
            api.description=calls one tool
            needs.url=https://example.test/needs.json
            needs.file=needs.json
            resetTool=wipe
            orders.tool=store
            orders.get=list
            orders.delete=remove
            orders.list=items
            database.tool=db
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals("x-task", params.verifyTask());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals("call", params.api().name());
        assertEquals("calls one tool", params.api().description());
        assertEquals("https://example.test/needs.json", params.needs().url());
        assertEquals("needs.json", params.needs().file());
        assertEquals("wipe", params.resetTool());
        assertEquals("store", params.orders().tool());
        assertEquals("list", params.orders().get());
        assertEquals("remove", params.orders().delete());
        assertEquals("items", params.orders().list());
        assertEquals("db", params.database().tool());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "api.name", "api.description", "needs.url", "needs.file",
            "resetTool", "orders.tool", "orders.get", "orders.delete", "orders.list", "database.tool"})
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
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "api.name", "api.description", "needs.url", "needs.file",
            "resetTool", "orders.tool", "orders.get", "orders.delete", "orders.list", "database.tool"})
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

    /** Stored stripped: a padded name would be sent at startup with its padding. */
    @Test
    void storesTheResetToolStripped() {
        String props = COMPLETE.replace("resetTool=wipe", "resetTool= wipe ");

        TaskParams params = new JavaPropsMapper().readValue(props, TaskParams.class);

        assertEquals("wipe", params.resetTool());
    }
}
