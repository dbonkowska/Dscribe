package io.github.dbonkowska.dscribe.labs.s03e01;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one error this pipeline cannot otherwise see.
 *
 * <p>A verdict array that came back shifted, short or long still fans out cleanly: every input
 * receives a stance, every stance is a legal member of the vocabulary, and the submitted set is
 * confidently wrong. Nothing downstream disagrees, because there is nothing downstream except a
 * hub that answers the whole submission with one word.
 *
 * <p>So each verdict carries its own index and the returned set is compared against the set that
 * was sent. That costs output tokens — the expensive half — on every call, deliberately, to turn
 * a silent misalignment into a refusal.
 *
 * <p>Driven by a scripted seam rather than a model: what is asserted is the decision, not a
 * provider's behaviour. Inputs and stances are invented.
 */
class JudgeTest {

    private final List<List<String>> calls = new ArrayList<>();

    private Judge judge(int batchSize, Verdicts... queued) {
        Deque<Verdicts> remaining = new ArrayDeque<>(List.of(queued));
        return new Judge(inputs -> {
            calls.add(List.copyOf(inputs));
            return remaining.poll();
        }, batchSize);
    }

    private static Verdicts verdicts(Verdicts.Verdict... verdicts) {
        return new Verdicts(List.of(verdicts));
    }

    private static Verdicts.Verdict at(int index, String stance) {
        return new Verdicts.Verdict(index, stance);
    }

    @Test
    void landsEachVerdictOnTheInputItWasAskedAbout() {
        Judge judge = judge(10, verdicts(at(0, "clear"), at(1, "concern"), at(2, "silent")));

        Map<String, String> stances = judge.stances(List.of("first", "second", "third"));

        assertEquals(
                Map.of("first", "clear", "second", "concern", "third", "silent"),
                stances);
    }

    /**
     * One index missing and one extra, in the same answer. Checking only the count would pass this
     * — three verdicts came back for three inputs — and the fan-out would then leave one input
     * unjudged while writing a stance against an input that does not exist.
     */
    @Test
    void refusesAnAnswerWhoseIndexSetDiffersFromTheInputs() {
        Judge judge = judge(10, verdicts(at(0, "clear"), at(2, "clear"), at(7, "concern")));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> judge.stances(List.of("first", "second", "third")));

        assertTrue(thrown.getMessage().contains("1"),
                () -> "it has to name the index nothing answered for: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("7"),
                () -> "and the index that answers for nothing: " + thrown.getMessage());
    }

    /**
     * Two verdicts for one input. The index set alone covers everything sent, so a set comparison
     * on its own waves this through and the second silently overwrites the first.
     */
    @Test
    void refusesAnAnswerRepeatingAnIndex() {
        Judge judge = judge(10, verdicts(at(0, "clear"), at(1, "concern"), at(1, "silent")));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> judge.stances(List.of("first", "second")));

        assertTrue(thrown.getMessage().contains("1"),
                () -> "it has to name the index answered twice: " + thrown.getMessage());
    }

    /** Batches are slices of the input, and each is numbered from zero within itself. */
    @Test
    void splitsTheInputIntoBatchesAndNumbersEachFromZero() {
        Judge judge = judge(
                2,
                verdicts(at(0, "clear"), at(1, "concern")),
                verdicts(at(0, "silent"), at(1, "clear")),
                verdicts(at(0, "concern")));

        Map<String, String> stances = judge.stances(List.of("a", "b", "c", "d", "e"));

        assertEquals(List.of(List.of("a", "b"), List.of("c", "d"), List.of("e")), calls);
        assertEquals("concern", stances.get("e"));
        assertEquals(5, stances.size());
    }

    /** Nothing to judge costs nothing: no call is made, rather than one carrying an empty list. */
    @Test
    void asksNothingWhenThereIsNothingToJudge() {
        Judge judge = judge(10);

        assertEquals(Map.of(), judge.stances(List.of()));
        assertEquals(List.of(), calls);
    }
}
