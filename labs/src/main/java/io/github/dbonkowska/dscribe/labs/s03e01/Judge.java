package io.github.dbonkowska.dscribe.labs.s03e01;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Judges distinct inputs in batches, and refuses an answer that does not line up with what was
 * asked.
 *
 * <p>The inputs handed here are already deduplicated, so each is judged once however many records
 * carry it. That is where this lesson's cost is won — not in triage, which decides very little,
 * but in the collapse from records to distinct values before a single call is made.
 *
 * <p>A seam of its own rather than a {@code ChatTransport}, for the reason {@code GridReader} has
 * one: {@code sendStructured} is declared on {@code LlmClient} and not on the library interface,
 * so a scripted fake is the only way to assert the decision rather than a provider's behaviour.
 *
 * <p>No reasoning field on the way back, unlike the delegated reading in s02e05. There the model's
 * account of how it counted was worth its tokens because one reading decided everything. Here
 * there are hundreds of verdicts and output is the half that costs, so the answer is as small as a
 * verdict can be: an index and a word.
 */
final class Judge {

    /** One call: some inputs out, a verdict per input back. */
    @FunctionalInterface
    interface Batch {
        Verdicts judge(List<String> inputs);
    }

    private final Batch batch;
    private final int batchSize;

    Judge(Batch batch, int batchSize) {
        this.batch = batch;
        this.batchSize = batchSize;
    }

    /**
     * Every input's stance, keyed by the input itself so the caller can fan it back out to
     * whatever carried it.
     *
     * <p>Batches are numbered from zero within themselves rather than across the whole input. The
     * model sees a short list and small numbers, and the check below is a comparison against one
     * slice rather than arithmetic against an offset — which is the kind of arithmetic that would
     * itself be a way to shift every verdict by a constant.
     */
    Map<String, String> stances(List<String> inputs) {
        Map<String, String> byInput = new LinkedHashMap<>();

        for (int from = 0; from < inputs.size(); from += batchSize) {
            List<String> slice = inputs.subList(from, Math.min(from + batchSize, inputs.size()));

            Verdicts answer = batch.judge(slice);
            aligned(slice, answer);

            for (Verdicts.Verdict verdict : answer.verdicts()) {
                byInput.put(slice.get(verdict.index()), verdict.stance());
            }
        }
        return byInput;
    }

    /**
     * The answer's indices must be exactly the indices that were sent — each once.
     *
     * <p>Three ways this goes wrong and two of them survive a weaker check. Counting the verdicts
     * passes an answer that dropped one index and invented another, and the fan-out then leaves an
     * input unjudged while writing a stance against an index that names nothing. Comparing the
     * sets passes an answer that repeated an index, where the second verdict silently overwrites
     * the first. So duplicates are caught before the sets are compared at all.
     *
     * <p>Refusing rather than salvaging, because a misaligned answer is indistinguishable from a
     * correct one downstream: every input still receives a legal stance, and the submission is
     * still well-formed.
     *
     * <p>Deliberately not proof. An answer numbered 0, 1, 2 whose <em>stances</em> are shifted by
     * one passes every check here — the index guards against the model mislabelling which input it
     * answered, not against it having read the wrong one. Nothing in a single answer could tell
     * the difference. What catches that is the labelled sample in {@code Evaluation}, where a
     * systematically displaced reading stops agreeing with hand labels.
     */
    private static void aligned(List<String> slice, Verdicts answer) {
        if (answer == null || answer.verdicts() == null) {
            throw new IllegalStateException(
                    "No verdicts came back for a batch of " + slice.size() + " inputs.");
        }

        Set<Integer> returned = new LinkedHashSet<>();
        for (Verdicts.Verdict verdict : answer.verdicts()) {
            if (!returned.add(verdict.index())) {
                throw new IllegalStateException(
                        "The answer gives index " + verdict.index() + " twice, for a batch of "
                                + slice.size() + " inputs. One of the two would silently overwrite"
                                + " the other, and nothing afterwards could tell which won.");
            }
        }

        Set<Integer> expected = new LinkedHashSet<>();
        for (int i = 0; i < slice.size(); i++) {
            expected.add(i);
        }

        Set<Integer> missing = new TreeSet<>(expected);
        missing.removeAll(returned);

        Set<Integer> extra = new TreeSet<>(returned);
        extra.removeAll(expected);

        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IllegalStateException(
                    "The answer's indices do not match the batch of " + slice.size()
                            + " inputs. Unanswered: " + missing + ". Answering for nothing: "
                            + extra + ". A shifted verdict array produces a confident wrong answer,"
                            + " so the batch is refused rather than salvaged.");
        }
    }
}
