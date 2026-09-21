package io.github.dbonkowska.dscribe.labs.s03e03;

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
 * from the file: a missing command key on the first command, a missing flag pattern after the
 * result was already earned.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            commandKey=cmd
            resetCommand=reset
            flagPattern=[{]F[}]
            crashPattern=CRASH
            commands.1=go
            commands.2=hold
            commands.3=back
            command.name=send
            command.description=sends one command
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals("x-task", params.verifyTask());
        assertEquals("cmd", params.commandKey());
        assertEquals("reset", params.resetCommand());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals("CRASH", params.crashPattern());
        assertEquals(List.of("go", "hold", "back"), params.commands(), "lists bind by index");
        assertEquals("send", params.command().name());
        assertEquals("sends one command", params.command().description());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "commandKey", "resetCommand", "flagPattern", "crashPattern",
            "command.name", "command.description"})
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
            "verifyTask", "commandKey", "resetCommand", "flagPattern", "crashPattern",
            "command.name", "command.description"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /** An empty enum is a tool that can send nothing, and a run with it cannot do anything. */
    @Test
    void refusesAFileWithNoCommands() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("commands."))
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("commands"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("sends nothing"),
                () -> "it has to say why an empty set is refused: " + thrown.getMessage());
    }

    /** A blank entry would put an empty string into the schema's enum. */
    @Test
    void refusesABlankCommand() {
        String props = COMPLETE.replace("commands.2=hold", "commands.2=");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("commands"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("blank"), thrown::getMessage);
    }

    /** It means the same as the entry before it, and makes the schema's enum ambiguous. */
    @Test
    void refusesACommandListedTwice() {
        String props = COMPLETE.replace("commands.3=back", "commands.3=hold");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("hold"), thrown::getMessage);
    }

    /**
     * The reset is the runner's. Where the model could send it, a run that crashed could be reset by
     * the model mid-run and the state that explains the crash would be gone.
     *
     * <p>Second position, different case, trailing space: a check that reads only the first entry,
     * or compares exactly, would pass all of these.
     */
    @Test
    void refusesACommandSetTheModelCouldResetFrom() {
        String props = COMPLETE.replace("commands.2=hold", "commands.2=Reset ");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("resetCommand"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("runner sends"),
                () -> "it has to say where the reset went instead: " + thrown.getMessage());
    }

    /**
     * A pattern that does not compile throws on the first reply it is applied to, after the run has
     * already spent and the environment has printed the result it was meant to find.
     */
    @ParameterizedTest
    @ValueSource(strings = {"flagPattern", "crashPattern"})
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
}
