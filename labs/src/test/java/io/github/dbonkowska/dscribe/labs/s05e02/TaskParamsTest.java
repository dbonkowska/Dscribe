package io.github.dbonkowska.dscribe.labs.s05e02;

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
 * Keys whose mistakes surface only once a session is open and its clock is running — or, worse,
 * as a wrong turn that burns it. Every one of them can be refused before anything is spent.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            startAction=open
            audioKey=sound
            voice=v1
            candidates.1=x
            candidates.2=y
            stages.1.say=Zażółć gęślą jaźń
            stages.2.say=second
            stages.3.say=third {chosen}
            interrupts.1.name=a
            interrupts.1.say=answer a
            interrupts.2.name=b
            interrupts.2.say=answer b
            """;

    private static TaskParams bind(String props) {
        return new JavaPropsMapper().readValue(props, TaskParams.class);
    }

    private static String refusal(String props) {
        return assertThrows(RuntimeException.class, () -> bind(props)).getMessage();
    }

    private static String without(String prefix) {
        return COMPLETE.lines().filter(line -> !line.startsWith(prefix)).collect(Collectors.joining("\n"));
    }

    @Test
    void bindsACompleteFile() {
        TaskParams params = bind(COMPLETE);

        assertEquals("x-task", params.verifyTask());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals("open", params.startAction());
        assertEquals("sound", params.audioKey());
        assertEquals("v1", params.voice());
        assertEquals(List.of("x", "y"), params.candidates());
        assertEquals(
                List.of(new TaskParams.Stage("Zażółć gęślą jaźń"), new TaskParams.Stage("second"),
                        new TaskParams.Stage("third {chosen}")),
                params.stages());
        assertEquals(
                List.of(new TaskParams.Interrupt("a", "answer a"), new TaskParams.Interrupt("b", "answer b")),
                params.interrupts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"verifyTask", "flagPattern", "startAction", "audioKey", "voice"})
    void refusesABlankRequiredKey(String key) {
        String message = refusal(COMPLETE.replaceAll("(?m)^" + key + "=.*$", key + "=  "));

        assertTrue(message.contains(key), () -> message);
    }

    /** Compiled here, so a typo fails before a session opens rather than on the reply carrying the flag. */
    @Test
    void refusesAFlagPatternThatDoesNotCompile() {
        String message = refusal(COMPLETE.replace("flagPattern=[{]F[}]", "flagPattern=["));

        assertTrue(message.contains("not a valid regex"), () -> message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stages", "candidates", "interrupts"})
    void refusesAMissingList(String list) {
        String message = refusal(without(list + "."));

        assertTrue(message.contains(list), () -> message);
    }

    /** Nothing has been heard before the first turn, so there is nothing it could name. */
    @Test
    void refusesTheChosenSlotInTheFirstStage() {
        String message = refusal(COMPLETE.replace("stages.1.say=Zażółć gęślą jaźń", "stages.1.say=hi {chosen}"));

        assertTrue(message.contains("{chosen}"), () -> message);
        assertTrue(message.contains("stages.1"), () -> message);
    }

    @Test
    void refusesTheChosenSlotInMoreThanOneStage() {
        String message = refusal(COMPLETE.replace("stages.2.say=second", "stages.2.say=second {chosen}"));

        assertTrue(message.contains("{chosen}"), () -> message);
    }

    /**
     * The classifier's labels are the interrupt names plus two the dialogue itself owns. An
     * interrupt sharing one would make a reply mean two things at once.
     */
    @ParameterizedTest
    @ValueSource(strings = {TaskParams.PROCEED, TaskParams.OTHER})
    void refusesAnInterruptNamedLikeAReservedLabel(String reserved) {
        String message = refusal(COMPLETE.replace("interrupts.1.name=a", "interrupts.1.name=" + reserved));

        assertTrue(message.contains(reserved), () -> message);
        assertTrue(message.contains("reserved"), () -> message);
    }

    @Test
    void refusesAnInterruptNamedTwice() {
        String message = refusal(COMPLETE.replace("interrupts.2.name=b", "interrupts.2.name=a"));

        assertTrue(message.contains("a"), () -> message);
        assertTrue(message.contains("twice"), () -> message);
    }

    @Test
    void refusesACandidateListedTwice() {
        String message = refusal(COMPLETE.replace("candidates.2=y", "candidates.2=x"));

        assertTrue(message.contains("twice"), () -> message);
    }

    @Test
    void refusesAnInterruptWithNothingToSay() {
        String message = refusal(COMPLETE.replace("interrupts.2.say=answer b", "interrupts.2.say= "));

        assertTrue(message.contains("interrupts.N.say"), () -> message);
    }
}
