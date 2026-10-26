package io.github.dbonkowska.dscribe.labs.s05e02;

import io.github.dbonkowska.dscribe.labs.s05e02.Dialogue.Burnt;
import io.github.dbonkowska.dscribe.labs.s05e02.Dialogue.Say;
import io.github.dbonkowska.dscribe.labs.s05e02.Dialogue.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one decision a session cannot afford to get wrong: what to say next. Every wrong answer
 * here burns a session with speech already paid for, which is why this per-lesson logic is
 * tested at all — see the constitution note in the spec.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class DialogueTest {

    private static final TaskParams TASK = new TaskParams(
            "x-task", "[{]F[}]", "open", "sound", "v1",
            List.of("x", "y"),
            List.of(new TaskParams.Stage("s1"), new TaskParams.Stage("s2"), new TaskParams.Stage("s3 {chosen}")),
            List.of(new TaskParams.Interrupt("a", "answer a"), new TaskParams.Interrupt("b", "answer b")));

    private static final List<String> NONE = List.of();

    @Test
    void opensWithTheFirstStageAlreadyRecorded() {
        Step opening = Dialogue.opening(TASK);

        assertEquals(new Say("s1", true), opening.next());
        assertEquals(1, opening.dialogue().stage());
    }

    @Test
    void movesToTheNextStageWhenTheReplyAnsweredThisOne() {
        Step step = Dialogue.opening(TASK).dialogue().step(TaskParams.PROCEED, NONE, "ok");

        assertEquals(new Say("s2", true), step.next());
        assertEquals(2, step.dialogue().stage());
    }

    /** An interruption is answered where it happens; the stage it interrupted is still the one waiting. */
    @Test
    void answersAnInterruptAndStaysWhereItWas() {
        Dialogue atTwo = Dialogue.opening(TASK).dialogue().step(TaskParams.PROCEED, NONE, "ok").dialogue();

        Step interrupted = atTwo.step("a", NONE, "?");

        assertEquals(new Say("answer a", true), interrupted.next());
        assertEquals(2, interrupted.dialogue().stage());
        assertEquals(3, interrupted.dialogue().step(TaskParams.PROCEED, List.of("x"), "ok").dialogue().stage());
    }

    /** Every stage said and no flag: there is nothing left that could earn one. */
    @Test
    void burnsWhenTheStagesRunOut() {
        Dialogue atThree = Dialogue.opening(TASK).dialogue()
                .step(TaskParams.PROCEED, List.of("x"), "ok").dialogue()
                .step(TaskParams.PROCEED, NONE, "ok").dialogue();

        Burnt burnt = assertInstanceOf(Burnt.class, atThree.step(TaskParams.PROCEED, NONE, "ok").next());

        assertTrue(burnt.reason().contains("stages ran out"), burnt::reason);
    }

    @Test
    void burnsOnAReplyNothingFitsAndQuotesIt() {
        Burnt burnt = assertInstanceOf(Burnt.class,
                Dialogue.opening(TASK).dialogue().step(TaskParams.OTHER, NONE, "hm?").next());

        assertTrue(burnt.reason().contains("hm?"), burnt::reason);
    }

    /** A later reply may add to what was chosen; one that names nothing has not shown it changed. */
    @Test
    void fillsTheChosenSlotFromAnEarlierReplyThatALaterOneDidNotErase() {
        Step step = Dialogue.opening(TASK).dialogue()
                .step(TaskParams.PROCEED, List.of("x"), "x is open").dialogue()
                .step(TaskParams.PROCEED, NONE, "ok");

        assertEquals(new Say("s3 x", false), step.next(), "a turn rendered now cannot have been recorded before");
    }

    @Test
    void joinsSeveralChosenInTheOrderTheCandidatesAreListed() {
        Step step = Dialogue.opening(TASK).dialogue()
                .step(TaskParams.PROCEED, List.of("y", "x"), "both").dialogue()
                .step(TaskParams.PROCEED, NONE, "ok");

        assertEquals(new Say("s3 x, y", false), step.next());
    }

    @Test
    void burnsRatherThanSayATurnWithNothingToName() {
        Step step = Dialogue.opening(TASK).dialogue()
                .step(TaskParams.PROCEED, NONE, "ok").dialogue()
                .step(TaskParams.PROCEED, NONE, "ok");

        Burnt burnt = assertInstanceOf(Burnt.class, step.next());
        assertTrue(burnt.reason().contains("nothing chosen before stage 3"), burnt::reason);
    }
}
