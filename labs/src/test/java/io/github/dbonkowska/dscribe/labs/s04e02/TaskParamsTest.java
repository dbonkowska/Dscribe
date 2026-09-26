package io.github.dbonkowska.dscribe.labs.s04e02;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose mistakes produce a run that cannot succeed, and here fail late: a wrong name is found
 * inside the service window, with the clock already running.
 *
 * <p>This bundle carries more than most — every name the hub's protocol uses, so that none is
 * written in code. Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            actions.help=about
            actions.start=begin
            actions.get=fetch
            actions.getResult=collect
            actions.config=store
            actions.sign=sign
            actions.done=finish
            documentation=manual
            jobs.1=first
            jobs.2=second
            protocol.actionField=verb
            protocol.paramField=what
            protocol.codeField=state
            protocol.sourceField=origin
            protocol.echoField=echo
            protocol.signatureField=seal
            protocol.timeoutField=ttl
            protocol.batchField=many
            protocol.signFields=/verbs/sign/needs
            protocol.configFields=/verbs/store/needs
            slotFields.1=day
            slotFields.2=time
            enums.mode.1=on
            enums.mode.2=off
            pendingCode=7
            pollIntervalMs=300
            safetyMarginMs=2000
            """;

    private static final String[] STRING_KEYS = {
            "verifyTask", "flagPattern",
            "actions.help", "actions.start", "actions.get", "actions.getResult", "actions.config",
            "actions.sign", "actions.done",
            "documentation",
            "protocol.actionField", "protocol.paramField", "protocol.codeField", "protocol.sourceField",
            "protocol.echoField", "protocol.signatureField", "protocol.timeoutField", "protocol.batchField",
            "protocol.signFields", "protocol.configFields"};

    @Test
    void bindsACompleteFile() {
        TaskParams params = bind(COMPLETE);

        assertEquals("x-task", params.verifyTask());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals(new TaskParams.Actions("about", "begin", "fetch", "collect", "store", "sign", "finish"),
                params.actions());
        assertEquals("manual", params.documentation());
        assertEquals(List.of("first", "second"), params.jobs());
        assertEquals(new TaskParams.Protocol("verb", "what", "state", "origin", "echo", "seal", "ttl", "many",
                "/verbs/sign/needs", "/verbs/store/needs"), params.protocol());
        assertEquals(List.of("day", "time"), params.slotFields());
        assertEquals(Map.of("mode", List.of("on", "off")), params.enums());
        assertEquals(7, params.pendingCode());
        assertEquals(300, params.pollIntervalMs());
        assertEquals(2000, params.safetyMarginMs());
    }

    /** A run with no closed vocabulary is a legitimate one: every field is then free text. */
    @Test
    void bindsAFileWithNoEnumsAsNone() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("enums."))
                .collect(Collectors.joining("\n"));

        assertEquals(Map.of(), bind(props).enums());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern",
            "actions.help", "actions.start", "actions.get", "actions.getResult", "actions.config",
            "actions.sign", "actions.done",
            "documentation",
            "protocol.actionField", "protocol.paramField", "protocol.codeField", "protocol.sourceField",
            "protocol.echoField", "protocol.signatureField", "protocol.timeoutField", "protocol.batchField",
            "protocol.signFields", "protocol.configFields",
            "pendingCode", "pollIntervalMs", "safetyMarginMs"})
    void refusesARequiredKeyThatWasNeverWritten(String key) {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith(key + "="))
                .collect(Collectors.joining("\n"));

        // Jackson wraps anything a record constructor throws, so the type on this path is its
        // wrapper rather than ours. The binding fails and the message names the key to edit.
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains(key),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    /** Present but empty is the same mistake as absent, and has to be refused the same way. */
    @Test
    void refusesARequiredKeyLeftBlank() {
        for (String key : STRING_KEYS) {
            String props = COMPLETE.lines()
                    .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                    .collect(Collectors.joining("\n"));

            RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props), key);

            assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
        }
    }

    /** No job queued is a run with nothing for the model to read. */
    @Test
    void refusesAFileWithNoJobs() {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(without("jobs.")));

        assertTrue(thrown.getMessage().contains("jobs"), thrown::getMessage);
    }

    /** Without slot fields a batch entry has no key, and every point would be filed under "". */
    @Test
    void refusesAFileWithNoSlotFields() {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(without("slotFields.")));

        assertTrue(thrown.getMessage().contains("slotFields"), thrown::getMessage);
    }

    /** An empty vocabulary is an enum no value satisfies, and the model could answer nothing. */
    @Test
    void refusesAnEnumWithNoValues() {
        String props = COMPLETE.replace("enums.mode.1=on\nenums.mode.2=off\n", "enums.mode.1=   \n");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("enums.mode"), thrown::getMessage);
    }

    /** The field lists are read from the help reply by pointer; a malformed one reads nothing. */
    @ParameterizedTest
    @ValueSource(strings = {"protocol.signFields", "protocol.configFields"})
    void refusesAPointerThatIsNotAJsonPointer(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=verbs/sign/needs" : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /**
     * A pattern that does not compile throws on the first reply it is applied to, after the window
     * has closed on the one reply that carried the result.
     */
    @Test
    void refusesAFlagPatternThatDoesNotCompile() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> bind(COMPLETE.replace("flagPattern=[{]F[}]", "flagPattern=[")));

        assertTrue(thrown.getMessage().contains("not a valid regex"), thrown::getMessage);
    }

    /** A zero interval polls the hub as fast as the network allows, for the whole window. */
    @Test
    void refusesAPollIntervalThatIsNotPositive() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> bind(COMPLETE.replace("pollIntervalMs=300", "pollIntervalMs=0")));

        assertTrue(thrown.getMessage().contains("pollIntervalMs"), thrown::getMessage);
    }

    /** A negative margin plans to finish after the window has closed. */
    @Test
    void refusesANegativeSafetyMargin() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> bind(COMPLETE.replace("safetyMarginMs=2000", "safetyMarginMs=-1")));

        assertTrue(thrown.getMessage().contains("safetyMarginMs"), thrown::getMessage);
    }

    private static String without(String prefix) {
        return COMPLETE.lines()
                .filter(line -> !line.startsWith(prefix))
                .collect(Collectors.joining("\n"));
    }

    private static TaskParams bind(String props) {
        return new JavaPropsMapper().readValue(props, TaskParams.class);
    }
}
