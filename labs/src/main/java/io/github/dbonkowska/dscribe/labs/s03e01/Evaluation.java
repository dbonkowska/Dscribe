package io.github.dbonkowska.dscribe.labs.s03e01;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * The gate again, one level up, for the branch where whole-level verdicts are what gets
     * submitted.
     *
     * <p>{@link #check} compares the labels against the verdicts on the parts. When the
     * composition measurement refuses the finer unit, the run judges wholes instead and submits
     * those — verdicts no label has been compared against. The labelled evidence does not
     * transfer, and the branch it fails to cover is the expensive one, taken precisely because
     * something already disagreed.
     *
     * <p>What survives the change of unit is <strong>one direction</strong> of the composition
     * rule: a whole containing a part labelled as claiming a problem must itself claim one. The
     * converse does not hold — a whole containing a part labelled clear may still claim a problem,
     * because another of its parts can, which is the composition rule rather than a contradiction
     * of it. A symmetric check would refuse correct runs, so only the sound direction is made.
     *
     * <p>Weaker than {@link #check}, and deliberately so rather than by oversight. It says nothing
     * about wholes carrying no labelled problem part, which is most of them.
     */
    static Passed checkDerived(
            List<Label> labels, Map<String, String> wholeStances, Composition composition) {

        Set<String> problemParts = new LinkedHashSet<>();
        for (Label label : labels) {
            if (composition.problem().equals(label.stance())) {
                problemParts.add(label.text());
            }
        }

        if (problemParts.isEmpty()) {
            throw new IllegalStateException(
                    "No label marks a problem, so nothing can be derived about whole-level"
                            + " verdicts: the only direction that survives the change of unit is"
                            + " \"a whole carrying a problem part claims a problem\". Label at"
                            + " least one phrase as '" + composition.problem() + "' in eval.json.");
        }

        List<String> disagreements = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<String, String> judged : wholeStances.entrySet()) {
            String carried = null;
            for (String part : composition.parts(judged.getKey())) {
                if (problemParts.contains(part)) {
                    carried = part;
                    break;
                }
            }
            if (carried == null) {
                continue;
            }

            checked++;
            if (!composition.problem().equals(judged.getValue())) {
                disagreements.add("'" + judged.getKey() + "' — judged " + judged.getValue()
                        + ", but it carries '" + carried + "', labelled "
                        + composition.problem());
            }
        }

        if (!disagreements.isEmpty()) {
            throw new IllegalStateException(
                    "Whole-level judgement contradicted " + disagreements.size()
                            + " label(s), so nothing was submitted:\n  "
                            + String.join("\n  ", disagreements));
        }

        return new Passed(checked);
    }
}
