package io.github.dbonkowska.dscribe.labs.s02e04;

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
 * Keys whose mistakes produce a working run that cannot succeed, or one that does what nobody
 * meant it to.
 *
 * <p>This lesson hands the file two things no earlier one did: the actions a model may call on an
 * API, and the format each answer field must have. The first is a safety line — an allowlist that
 * happens to contain the reset action gives the model the one call with a side effect, and nothing
 * in a run would say so until the source had been reset under it. The second fails at the far end:
 * a pattern that does not compile throws on the first submission, after the loop has been paid
 * for, and a field listed twice makes every submission ambiguous about which value is meant.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    private static final TaskParams.ToolPrompt PROMPT = new TaskParams.ToolPrompt("n", "d");
    private static final TaskParams.Api API =
            new TaskParams.Api("/x/api", "wipe", "about", List.of("find", "open"));

    private static TaskParams with(List<TaskParams.Field> fields) {
        return new TaskParams("x-task", "[{]F[}]", API, fields, PROMPT, PROMPT, PROMPT);
    }

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            api.path=/x/api
            api.resetAction=wipe
            api.helpAction=about
            api.actions.1=find
            api.actions.2=open
            fields.1.name=when
            fields.1.pattern=[0-9]{4}
            fields.2.name=word
            fields.2.pattern=[a-z]+
            call.name=call
            call.description=calls
            submit.name=send
            submit.description=sends %s
            answer.name=done
            answer.description=finishes
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals(List.of("find", "open"), params.api().actions(), "lists bind by index");
        assertEquals(
                List.of(new TaskParams.Field("when", "[0-9]{4}"), new TaskParams.Field("word", "[a-z]+")),
                params.fields());
        assertEquals("/x/api", params.api().path());
        assertEquals("send", params.submit().name());
    }

    /**
     * A missing string key binds to null without complaint. Without the path or the reset action
     * the run dies on its first call to the API; without the task name every submission reaches the
     * hub malformed and reads as a wrong answer; without the flag pattern the run dies after the
     * result has already been earned.
     */
    @ParameterizedTest
    @ValueSource(strings = {"verifyTask", "flagPattern", "api.path", "api.resetAction", "api.helpAction"})
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
    @ValueSource(strings = {"verifyTask", "flagPattern", "api.path", "api.resetAction", "api.helpAction"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /**
     * The one mistake here that does harm rather than wasting a run: the model would be offered the
     * action that resets the source, and could call it mid-search.
     */
    @Test
    void refusesAnAllowlistHoldingTheResetAction() {
        String props = COMPLETE + "api.actions.3=wipe\n";

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("api.actions"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("wipe"),
                () -> "it has to name the action to remove: " + thrown.getMessage());
    }

    /** An empty enum is a schema no value satisfies: the tool is offered and never callable. */
    @Test
    void refusesAnAllowlistWithNoActions() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("api.actions."))
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("api.actions"), thrown::getMessage);
    }

    /** No fields means every submission is empty, and refused for it, forever. */
    @Test
    void refusesAFileWithNoFields() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("fields."))
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("fields"), thrown::getMessage);
    }

    /** Two slots of one name: a submission cannot fill both, so it can never be complete. */
    @Test
    void refusesAFieldNamedTwice() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> with(List.of(
                        new TaskParams.Field("when", "[0-9]{4}"),
                        new TaskParams.Field("when", "[a-z]+"))));

        assertTrue(thrown.getMessage().contains("when"), thrown::getMessage);
    }

    @Test
    void refusesAFieldPatternThatDoesNotCompile() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new TaskParams.Field("when", "[0-9"));

        assertTrue(thrown.getMessage().contains("fields"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("when"),
                () -> "it has to name the field whose pattern is broken: " + thrown.getMessage());
    }

    /** A blank pattern matches only the empty value, so every real value would be refused. */
    @Test
    void refusesAFieldWithABlankPattern() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new TaskParams.Field("when", "  "));

        assertTrue(thrown.getMessage().contains("when"), thrown::getMessage);
    }
}
