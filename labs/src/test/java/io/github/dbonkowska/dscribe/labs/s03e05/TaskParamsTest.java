package io.github.dbonkowska.dscribe.labs.s03e05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose mistakes produce a run that cannot succeed, or one that sends the hub key somewhere it
 * should not go.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            searchPath=/api/x-search
            flagPattern=[{]F[}]
            search.name=find
            search.description=finds tools
            call.name=use
            call.description=uses a found tool
            submit.name=send
            submit.description=sends the answer
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals("x-task", params.verifyTask());
        assertEquals("/api/x-search", params.searchPath());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals("find", params.search().name());
        assertEquals("finds tools", params.search().description());
        assertEquals("use", params.call().name());
        assertEquals("uses a found tool", params.call().description());
        assertEquals("send", params.submit().name());
        assertEquals("sends the answer", params.submit().description());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "searchPath", "flagPattern",
            "search.name", "search.description",
            "call.name", "call.description",
            "submit.name", "submit.description"})
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
            "verifyTask", "searchPath", "flagPattern",
            "search.name", "search.description",
            "call.name", "call.description",
            "submit.name", "submit.description"})
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
    }

    /**
     * The hub key is merged into whatever is posted to this path, and the path is appended to the
     * hub's base URL. Anything that is not a plain path can move the request to another host.
     */
    @ParameterizedTest
    @ValueSource(strings = {"https://elsewhere.example/x", "//elsewhere.example/x", "@elsewhere.example/x"})
    void refusesASearchPathThatIsNotAPlainPath(String value) {
        String props = COMPLETE.replace("searchPath=/api/x-search", "searchPath=" + value);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("searchPath"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains(value), thrown::getMessage);
    }
}
