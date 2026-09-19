package io.github.dbonkowska.dscribe.labs.s02e05;

import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam in front of the one call that is judged.
 *
 * <p>Thin by design: every judgement about a sequence belongs to {@link Commands}, and what is left
 * here is sending what passed and handing back what came back. What these tests are really about is
 * that a refused sequence costs nothing — the hub is never reached, so a rejection in the transcript
 * always means the hub rejected something, never that this code did.
 *
 * <p>Every "nothing was sent" assertion sits after the call it is about. The command language is
 * invented: nothing in this file is supplied by the exercise.
 */
class SubmitToolTest {

    private static final TaskParams.Dsl DSL = new TaskParams.Dsl(
            List.of(
                    new TaskParams.Shape("alt", "set", "^[0-9]+u$"),
                    new TaskParams.Shape("sec", "set", "^[0-9]+,[0-9]+$")),
            List.of("go"),
            "go",
            List.of("alt", "sec"),
            "sec",
            "set(%s,%s)");

    private static final String RESERVED = "set(3,4)";
    private static final List<String> VALID = List.of("set(5u)", "set(3,4)", "go");

    private record Sent(String label, Object answer) {}

    /** What the fake hub was handed, in order. */
    private final List<Sent> sent = new ArrayList<>();

    private SubmitTool submitTool() {
        return new SubmitTool(
                (label, answer) -> {
                    sent.add(new Sent(label, answer));
                    return "{\"n\":" + sent.size() + "}";
                },
                new Commands(DSL, RESERVED));
    }

    private Tool<SubmitTool.Submission> tool() {
        return submitTool().tool("send", "sends a sequence");
    }

    @Test
    void sendsTheSequenceUnderTheKeyTheHubExpects() {
        tool().handler().apply(new SubmitTool.Submission(VALID));

        assertEquals(List.of(Map.of("instructions", VALID)), sent.stream().map(Sent::answer).toList());
    }

    /**
     * Order is the model's decision and the one thing the sequence carries that nothing else does,
     * so it reaches the hub exactly as written.
     */
    @Test
    void keepsTheOrderTheModelWrote() {
        tool().handler().apply(new SubmitTool.Submission(VALID));

        assertEquals(VALID, ((Map<?, ?>) sent.getFirst().answer()).get("instructions"));
    }

    /** The hub's account of what is wrong is the only guidance the next attempt gets. */
    @Test
    void returnsTheReplyVerbatim() {
        Object result = tool().handler().apply(new SubmitTool.Submission(VALID)).result();

        assertEquals("{\"n\":1}", result);
    }

    /** Labels are what make a transcript readable when several attempts run together. */
    @Test
    void labelsEachAttemptInOrder() {
        Tool<SubmitTool.Submission> tool = tool();

        tool.handler().apply(new SubmitTool.Submission(VALID));
        tool.handler().apply(new SubmitTool.Submission(VALID));

        assertEquals(List.of("submission 1", "submission 2"), sent.stream().map(Sent::label).toList());
    }

    /**
     * The point of the whole class. A sequence the check refuses must cost nothing — otherwise a
     * rejection in the transcript is ambiguous between the hub disagreeing and this code doing so.
     */
    @Test
    void refusesASequenceTheCheckRejectsWithoutSendingAnything() {
        Tool<SubmitTool.Submission> tool = tool();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new SubmitTool.Submission(List.of("set(5u)", "set(3,4)"))));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("go"),
                () -> "the refusal is the check's own, passed through: " + thrown.getMessage());
    }

    /** The reserved rule reaches the hub call through the same path as every other refusal. */
    @Test
    void refusesATransposedReservedCommandWithoutSendingAnything() {
        Tool<SubmitTool.Submission> tool = tool();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new SubmitTool.Submission(List.of("set(5u)", "set(4,3)", "go"))));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains(RESERVED), thrown::getMessage);
    }

    /**
     * The result is in what the hub said, not in what the model reports about it. The runner reads
     * it from these rather than asking the model to repeat it, so a plausible-looking answer that
     * no reply ever contained cannot end the run.
     */
    @Test
    void keepsEveryReplyTheHubReturned() {
        SubmitTool submit = submitTool();
        Tool<SubmitTool.Submission> tool = submit.tool("send", "sends a sequence");

        tool.handler().apply(new SubmitTool.Submission(VALID));
        tool.handler().apply(new SubmitTool.Submission(VALID));

        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), submit.responses());
    }

    /** A refused sequence never reached the hub, so it left no reply to keep. */
    @Test
    void keepsNoReplyForASequenceItRefused() {
        SubmitTool submit = submitTool();
        Tool<SubmitTool.Submission> tool = submit.tool("send", "sends a sequence");

        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new SubmitTool.Submission(List.of("set(5u)", "set(3,4)"))));

        assertEquals(List.of(), submit.responses());
    }

    /**
     * A provider that ignores the schema can leave the list out. Refused as something the model can
     * supply, rather than reaching it as "Tool failed: NullPointerException".
     */
    @Test
    void refusesASubmissionWithNoInstructionsWithoutSendingAnything() {
        Tool<SubmitTool.Submission> tool = tool();

        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new SubmitTool.Submission(null)));

        assertEquals(List.of(), sent);
    }
}
