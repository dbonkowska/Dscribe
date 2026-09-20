package io.github.dbonkowska.dscribe.labs.s03e01;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Measures whether the cheap unit may stand in for the expensive one, instead of assuming it.
 *
 * <p>The corpus repeats itself twice over: many records share a note, and the notes themselves are
 * assembled from a much smaller bank of parts. Judging the parts rather than the notes is an order
 * of magnitude cheaper, and it is only correct if a note claims a problem exactly when one of its
 * parts does.
 *
 * <p>That is a claim about the data, not a property of the design. The bank carries opposite
 * stances, so a note can mix them, and a later part withdrawing what an earlier one said would
 * make the composition rule quietly wrong — every note containing one alarmed phrase would be
 * flagged, and nothing would say so. So a sample is judged both ways and the two are compared.
 *
 * <p>Only the problem stance matters to the comparison. Composing a non-problem stance out of
 * several parts would mean inventing a rule for which of them wins; whether the whole claims
 * trouble is the only question the submission turns on, so that is the only question asked.
 */
final class Composition {

    /** Which unit the run judges the rest of the corpus in. */
    enum Unit { CLAUSE, NOTE }

    /**
     * @param rate      the share of the sample where composing from parts matched the whole
     * @param unit      what that rate, against the configured threshold, decided
     * @param disagreed the wholes where the two differed — named, not counted, because a rate on
     *                  its own gives nobody anything to look at
     */
    record Agreement(double rate, Unit unit, List<String> disagreed) {}

    private final Pattern separator;
    private final String problem;
    private final double threshold;

    Composition(String clauseSeparator, String problem, double threshold) {
        // quoted: a literal from the bundle, not a pattern the exercise wrote
        this.separator = Pattern.compile(Pattern.quote(clauseSeparator));
        this.problem = problem;
        this.threshold = threshold;
    }

    /** The stance that means trouble — the only one the submission turns on. */
    String problem() {
        return problem;
    }

    /**
     * Splits a whole into its parts.
     *
     * <p>Stripped of surrounding space and of a single trailing stop, so that the same phrase is
     * one part wherever it sits. Were the same words to end one whole and sit inside another, the
     * punctuation alone would make them two parts to judge separately — a needless call, and two
     * verdicts free to disagree with each other.
     */
    List<String> parts(String whole) {
        List<String> parts = new ArrayList<>();
        for (String part : separator.split(whole)) {
            String trimmed = part.strip();
            if (trimmed.endsWith(".")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
            }
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    Agreement over(
            List<String> sample,
            Map<String, String> wholeStances,
            Map<String, String> partStances) {

        List<String> disagreed = new ArrayList<>();
        int matched = 0;

        for (String whole : sample) {
            String stance = wholeStances.get(whole);
            if (stance == null) {
                throw new IllegalStateException(
                        "Nothing judged the sampled whole '" + whole + "', so there is no coarse"
                                + " verdict to compare a composed one against.");
            }

            if (composedClaimsProblem(whole, partStances) == problem.equals(stance)) {
                matched++;
            } else {
                disagreed.add(whole);
            }
        }

        double rate = sample.isEmpty() ? 0 : (double) matched / sample.size();
        return new Agreement(rate, rate >= threshold ? Unit.CLAUSE : Unit.NOTE, disagreed);
    }

    /**
     * Any part claiming a problem makes the whole claim one — the rule under test.
     *
     * <p>Every part is consulted, and an unjudged one is refused rather than read as silence. Read
     * as silence, a whole whose only problem sits in the missing part composes to clear, and the
     * rate then measures a gap in the inputs while reporting it as a fact about the language.
     */
    boolean composedClaimsProblem(String whole, Map<String, String> partStances) {
        boolean claims = false;
        for (String part : parts(whole)) {
            String stance = partStances.get(part);
            if (stance == null) {
                throw new IllegalStateException(
                        "Nothing judged the part '" + part + "' of '" + whole + "'. Treated as"
                                + " silence it would compose away a problem claimed nowhere else.");
            }
            if (problem.equals(stance)) {
                claims = true;
            }
        }
        return claims;
    }

    /**
     * The same question read straight off a coarse verdict, for the branch where the finer unit
     * was not trusted.
     *
     * <p>Refuses an unjudged whole for the reason its composed sibling refuses an unjudged part.
     * A plain {@code problem.equals(map.get(whole))} answers {@code false} for a missing entry, so
     * a note nothing judged would be submitted as clean — silence read as a verdict, and in the
     * one direction this run has no way to check.
     */
    boolean judgedAsProblem(String whole, Map<String, String> wholeStances) {
        String stance = wholeStances.get(whole);
        if (stance == null) {
            throw new IllegalStateException(
                    "Nothing judged '" + whole + "'. Treated as silence it would submit every"
                            + " record carrying it as clean.");
        }
        return problem.equals(stance);
    }
}
