package io.github.dbonkowska.dscribe.labs.s03e01;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The gate in front of the one submission: the model's judgement against a sample labelled by
 * hand.
 *
 * <p>Every other check in this run is internally consistent by construction. The rules agree with
 * themselves; the index check agrees with the batch it sent; the composition check compares a
 * model against the same model. None of those is an outside opinion. The hub is, and it is asked
 * once and answers the whole submission with one word — so without this, a run whose model read
 * the corpus badly would pass everything and learn nothing until the answer was already wrong.
 *
 * <p>This is also the only thing covering the gap {@code Judge} names: an answer whose stances are
 * displaced relative to their indices is well-formed, survives alignment, and stops agreeing with
 * hand labels at once.
 *
 * <p><strong>The labels sit on the judged unit, not on records.</strong> Labelling a record would
 * mean applying the range rules by hand — reproducing, more slowly and less reliably, a
 * computation the code already does exactly. That is not an independent oracle, it is the same
 * oracle with a worse implementation. Reading a phrase and saying whether it claims trouble is
 * something a person can do and this code cannot, which is what makes it worth asking for. The
 * deterministic pass is covered instead by boundary cases in {@code RulesTest}, which examine it
 * more sharply than any sample of real records would.
 */
final class Evaluation {

    /** @param checked how many labels were compared, for the transcript */
    record Passed(int checked) {}

    private Evaluation() {}

    /**
     * Throws unless the model agreed with every label.
     *
     * <p>Every disagreement is collected before throwing rather than the first one reported. One
     * name reads as a single bad label; a list of them is what a systematically displaced reading
     * looks like, and telling those apart decides whether to fix the labels or the prompt.
     *
     * <p>Both stances appear in the message, because the label is as likely to be wrong as the
     * model — these are hand-written, and the person who wrote them is the person reading this.
     */
    static Passed check(List<Label> labels, Map<String, String> stances) {
        if (labels.isEmpty()) {
            throw new IllegalStateException(
                    "No labels to check against. A gate that compares nothing passes everything and"
                            + " reports that it passed. Write the sample in the lesson bundle's"
                            + " eval.json before running.");
        }

        List<String> disagreements = new ArrayList<>();

        for (Label label : labels) {
            String judged = stances.get(label.text());

            if (judged == null) {
                throw new IllegalStateException(
                        "Nothing judged '" + label.text() + "', which eval.json labels as '"
                                + label.stance() + "'. A label pointing at text the corpus no longer"
                                + " contains is stale, and skipping it quietly shrinks the gate"
                                + " every time the corpus moves.");
            }

            if (!judged.equals(label.stance())) {
                disagreements.add(
                        "'" + label.text() + "' — labelled " + label.stance() + ", judged " + judged);
            }
        }

        if (!disagreements.isEmpty()) {
            throw new IllegalStateException(
                    "The model disagreed with " + disagreements.size() + " of " + labels.size()
                            + " labels, so nothing was submitted:\n  "
                            + String.join("\n  ", disagreements)
                            + "\nFix the prompt, or the label if the label is the wrong one.");
        }

        return new Passed(labels.size());
    }
}
