package io.github.dbonkowska.dscribe.labs.s03e01;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last thing that can say no.
 *
 * <p>Everything before this is internally consistent by construction: the rules agree with
 * themselves, the index check agrees with the batch it was sent, the composition check agrees with
 * a model it also asked. None of that is an outside opinion. The hub is the only outside opinion,
 * it is consulted once, and it answers the whole submission with one word — so a run whose model
 * read the corpus backwards would sail through every earlier check and find out nothing.
 *
 * <p>This is also what covers the gap {@code Judge} names and cannot close: an answer whose
 * stances are shifted relative to its indices is well-formed, passes alignment, and stops
 * agreeing with hand labels immediately.
 *
 * <p>Labels here are invented, and the stances are a two-word vocabulary for legibility.
 */
class EvaluationTest {

    private static final String PROBLEM = "concern";
    private static final String CLEAR = "clear";

    private static List<Label> labels(String... textThenStance) {
        List<Label> labels = new ArrayList<>();
        for (int i = 0; i < textThenStance.length; i += 2) {
            labels.add(new Label(textThenStance[i], textThenStance[i + 1]));
        }
        return labels;
    }

    private static Map<String, String> judged(String... textThenStance) {
        Map<String, String> stances = new LinkedHashMap<>();
        for (int i = 0; i < textThenStance.length; i += 2) {
            stances.put(textThenStance[i], textThenStance[i + 1]);
        }
        return stances;
    }

    @Test
    void passesWhenTheModelAgreesWithEveryLabel() {
        Evaluation.Passed passed = Evaluation.check(
                labels("all steady", CLEAR, "figures look wrong", PROBLEM, "signed off", CLEAR),
                judged("all steady", CLEAR, "figures look wrong", PROBLEM, "signed off", CLEAR));

        assertEquals(3, passed.checked());
    }

    /**
     * One disagreement ends the run. Not a warning: the submission is single-shot and its
     * rejection names nothing, so the one piece of ground truth available is not something to
     * proceed past.
     */
    @Test
    void refusesOnASingleDisagreement() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                Evaluation.check(
                        labels("all steady", CLEAR, "figures look wrong", PROBLEM),
                        judged("all steady", CLEAR, "figures look wrong", CLEAR)));

        assertTrue(thrown.getMessage().contains("figures look wrong"),
                () -> "it has to name what was misjudged: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(PROBLEM) && thrown.getMessage().contains(CLEAR),
                () -> "and both stances, so the label can be fixed if the label is wrong: "
                        + thrown.getMessage());
    }

    /**
     * Every disagreement, not the first. One name suggests one bad label; a list of them is how a
     * systematically displaced reading looks, and the difference decides what to go and fix.
     */
    @Test
    void namesEveryDisagreementRatherThanStoppingAtTheFirst() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                Evaluation.check(
                        labels("first", CLEAR, "second", PROBLEM, "third", PROBLEM),
                        judged("first", PROBLEM, "second", PROBLEM, "third", CLEAR)));

        assertTrue(thrown.getMessage().contains("first"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("third"), thrown::getMessage);
    }

    /**
     * A label pointing at text nothing judged. Skipped quietly, the gate shrinks every time the
     * corpus shifts under it and still reports that it passed.
     */
    @Test
    void refusesALabelNothingJudged() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                Evaluation.check(
                        labels("all steady", CLEAR, "a phrase since rewritten", PROBLEM),
                        judged("all steady", CLEAR)));

        assertTrue(thrown.getMessage().contains("a phrase since rewritten"), thrown::getMessage);
    }

    /** A gate that checks nothing passes everything, and says "passed" while doing it. */
    @Test
    void refusesAnEmptyLabelSet() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Evaluation.check(List.of(), judged("all steady", CLEAR)));

        assertTrue(thrown.getMessage().toLowerCase().contains("label"), thrown::getMessage);
    }
}
