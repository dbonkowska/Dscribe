package io.github.dbonkowska.dscribe.labs.s05e01;

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
 * rather than failing, and an int nobody wrote binds to 0 — which, as the end code, would never
 * match and leave the run listening until its cap.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            actions.start=open
            actions.listen=poll
            actions.transmit=send
            endCode=7
            noiseMarkers.1=hum
            noiseMarkers.2=fizz
            fields.1.name=place
            fields.1.format=text
            fields.2.name=size
            fields.2.format=decimal2
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals("x-task", params.verifyTask());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals("open", params.actions().start());
        assertEquals("poll", params.actions().listen());
        assertEquals("send", params.actions().transmit());
        assertEquals(7, params.endCode());
        assertEquals(List.of("hum", "fizz"), params.noiseMarkers());
        assertEquals(
                List.of(new TaskParams.Field("place", "text"), new TaskParams.Field("size", "decimal2")),
                params.fields());
    }

    /** With no fields there is nothing to ask the model for and nothing to send. */
    @Test
    void refusesAFileWithNoFields() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("fields."))
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("fields"), thrown::getMessage);
    }

    @Test
    void refusesAFieldWithABlankName() {
        String props = COMPLETE.replace("fields.1.name=place", "fields.1.name=  ");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("fields.N.name"), thrown::getMessage);
    }

    /** A format the report cannot apply would be found only after every tier had been paid for. */
    @Test
    void refusesAFormatTheReportDoesNotKnow() {
        String props = COMPLETE.replace("fields.2.format=decimal2", "fields.2.format=money");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("money"), thrown::getMessage);
    }

    @Test
    void refusesAFieldNamedTwice() {
        String props = COMPLETE.replace("fields.2.name=size", "fields.2.name=place");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("place"), thrown::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "actions.start", "actions.listen", "actions.transmit", "endCode"})
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
            "verifyTask", "flagPattern", "actions.start", "actions.listen", "actions.transmit"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /** No end code the hub could send is zero or below, so either is a key nobody set properly. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void refusesAnEndCodeThatCannotBeTheHubs(String value) {
        String props = COMPLETE.replace("endCode=7", "endCode=" + value);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("endCode"), thrown::getMessage);
    }

    /** The noise count in the log is the only thing they feed, but a run with none counts nothing. */
    @Test
    void refusesAnEmptyNoiseMarkerList() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("noiseMarkers"))
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("noiseMarkers"), thrown::getMessage);
    }

    /**
     * A pattern that does not compile throws on the final reply, after the run has spent and the
     * hub has printed the result it was meant to find.
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

    /** Stored stripped: a padded action would be sent to the hub with its padding. */
    @Test
    void storesActionsStripped() {
        String props = COMPLETE.replace("actions.listen=poll", "actions.listen= poll ");

        assertEquals("poll", new JavaPropsMapper().readValue(props, TaskParams.class).actions().listen());
    }
}
