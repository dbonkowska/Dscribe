package io.github.dbonkowska.dscribe.labs.s03e02;

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
 * Keys whose mistakes produce a run that cannot succeed, or one that does what nobody meant.
 *
 * <p>A string key nobody wrote binds to null rather than failing, and would fail later and further
 * from the file: a missing shell path on the first command, a missing flag pattern after the result
 * was already earned.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            codePattern=[a-f0-9]{8}
            answerKey=code
            shell.path=/x/shell
            shell.commandKey=cmd
            shell.helpCommand=help
            forbidden.1=a
            forbidden.2=b
            transientCodes.1=BUSY
            causedCodes.1=LOCKED
            command.name=run
            command.description=runs a command
            submit.name=send
            submit.description=sends the code
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals(List.of("a", "b"), params.forbidden(), "lists bind by index");
        assertEquals(List.of("BUSY"), params.transientCodes());
        assertEquals(List.of("LOCKED"), params.causedCodes());
        assertEquals("cmd", params.shell().commandKey());
        assertEquals("code", params.answerKey());
        assertEquals("send", params.submit().name());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "codePattern", "answerKey", "shell.path", "shell.commandKey",
            "shell.helpCommand", "command.name", "command.description", "submit.name",
            "submit.description"})
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
            "verifyTask", "flagPattern", "codePattern", "answerKey", "shell.path", "shell.commandKey",
            "shell.helpCommand", "command.name", "command.description", "submit.name",
            "submit.description"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /** An empty list guards nothing, and a guard that guards nothing looks exactly like one that works. */
    @Test
    void refusesAFileWithNoForbiddenRoots() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("forbidden."))
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("forbidden"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("no-op"),
                () -> "it has to say why an empty list is refused: " + thrown.getMessage());
    }

    /** A blank entry would match every command. */
    @Test
    void refusesABlankForbiddenEntry() {
        String props = COMPLETE.replace("forbidden.2=b", "forbidden.2=");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("forbidden"), thrown::getMessage);
    }

    /** Whichever branch checked the code first would silently win, and the other kind never fire. */
    @Test
    void refusesACodeThatMeansTwoThings() {
        String props = COMPLETE.replace("causedCodes.1=LOCKED", "causedCodes.1=BUSY");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("BUSY"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("transientCodes"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("causedCodes"), thrown::getMessage);
    }

    /**
     * A pattern that does not compile throws on the first reply it is applied to, after the run has
     * already spent and the environment has printed the code it was meant to find.
     */
    @ParameterizedTest
    @ValueSource(strings = {"codePattern", "flagPattern"})
    void refusesAPatternThatDoesNotCompile(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=[a-z" : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("backslash"),
                () -> "it has to say how properties files mangle patterns: " + thrown.getMessage());
    }

    @Test
    void bindsAPatternThatCompiles() {
        String props = COMPLETE.replace("codePattern=[a-f0-9]{8}", "codePattern=[a-z]{40}");

        TaskParams params = new JavaPropsMapper().readValue(props, TaskParams.class);

        assertEquals("[a-z]{40}", params.codePattern());
    }
}
