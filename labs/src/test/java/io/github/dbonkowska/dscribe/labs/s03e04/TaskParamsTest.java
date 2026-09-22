package io.github.dbonkowska.dscribe.labs.s03e04;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose mistakes produce a run that cannot succeed, or one that does what nobody meant.
 * Follows {@code s03e03.TaskParams}'s refuse-at-binding shape.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            toolPath=/tool
            tool.name=find_cities
            tool.description=Finds cities selling an item described in natural language.
            citiesFile=cities.csv
            itemsFile=items.csv
            connectionsFile=connections.csv
            replyMinBytes=4
            replyMaxBytes=500
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals("x-task", params.verifyTask());
        assertEquals("/tool", params.toolPath());
        assertEquals("find_cities", params.tool().name());
        assertEquals("Finds cities selling an item described in natural language.", params.tool().description());
        assertEquals("cities.csv", params.citiesFile());
        assertEquals("items.csv", params.itemsFile());
        assertEquals("connections.csv", params.connectionsFile());
        assertEquals(4, params.replyMinBytes());
        assertEquals(500, params.replyMaxBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "toolPath", "tool.name", "tool.description",
            "citiesFile", "itemsFile", "connectionsFile"})
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
            "verifyTask", "toolPath", "tool.name", "tool.description",
            "citiesFile", "itemsFile", "connectionsFile"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /** A pair nothing could ever satisfy — every reply would violate one bound or the other. */
    @Test
    void refusesAByteRangeWhereTheMinimumExceedsTheMaximum() {
        String props = COMPLETE
                .replace("replyMinBytes=4", "replyMinBytes=500")
                .replace("replyMaxBytes=500", "replyMaxBytes=4");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("500"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("4"), thrown::getMessage);
    }
}
