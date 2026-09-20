package io.github.dbonkowska.dscribe.labs.s03e01;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the cheap unit may stand in for the expensive one.
 *
 * <p>Judging the smaller unit costs a fraction of judging the larger, and the whole saving rests
 * on an assumption nothing has checked: that a whole claims a problem exactly when one of its
 * parts does. The parts can carry opposite stances, so the assumption is not obviously true — a
 * later part could walk back an earlier one. This measures it on a sample instead of believing it,
 * and falls back when the measurement disagrees.
 *
 * <p>Notes and stances here are invented, and deliberately bland: what is under test is the
 * arithmetic and the fallback, not anybody's judgement about phrasing.
 */
class CompositionTest {

    private static final String PROBLEM = "concern";

    private static final String CLEAR_1 = "all steady, nothing of note, closing out";
    private static final String CLEAR_2 = "quiet run, within limits, signed off";
    private static final String TAIL_PROBLEM = "opened normally, held steady, the figures look wrong";
    private static final String HEAD_PROBLEM = "the figures look wrong, held steady, closed anyway";

    private static Composition composition(double threshold) {
        return new Composition(",", PROBLEM, threshold);
    }

    private static Map<String, String> noteStances(String... noteThenStance) {
        Map<String, String> stances = new LinkedHashMap<>();
        for (int i = 0; i < noteThenStance.length; i += 2) {
            stances.put(noteThenStance[i], noteThenStance[i + 1]);
        }
        return stances;
    }

    private static Map<String, String> clauseStancesFor(String... notes) {
        Map<String, String> stances = new LinkedHashMap<>();
        for (String note : notes) {
            for (String clause : note.split(",")) {
                String trimmed = clause.strip();
                stances.put(trimmed, trimmed.contains("wrong") ? PROBLEM : "clear");
            }
        }
        return stances;
    }

    @Test
    void trustsTheFinerUnitWhenEveryComposedStanceAgrees() {
        Composition.Agreement agreement = composition(0.9).over(
                List.of(CLEAR_1, CLEAR_2, TAIL_PROBLEM),
                noteStances(CLEAR_1, "clear", CLEAR_2, "clear", TAIL_PROBLEM, PROBLEM),
                clauseStancesFor(CLEAR_1, CLEAR_2, TAIL_PROBLEM));

        assertEquals(1.0, agreement.rate());
        assertEquals(Composition.Unit.CLAUSE, agreement.unit());
        assertEquals(List.of(), agreement.disagreed());
    }

    /**
     * The problem is claimed by the <em>last</em> part only. An implementation that composes from
     * the head — or from any single part — reads this whole as clear, disagrees with the note, and
     * reports a rate of zero. Put the same clause first and that implementation passes.
     */
    @Test
    void composesAProblemClaimedOnlyByTheFinalPart() {
        Composition.Agreement agreement = composition(0.9).over(
                List.of(TAIL_PROBLEM),
                noteStances(TAIL_PROBLEM, PROBLEM),
                clauseStancesFor(TAIL_PROBLEM));

        assertEquals(1.0, agreement.rate());
        assertEquals(Composition.Unit.CLAUSE, agreement.unit());
    }

    /**
     * The disagreement this exists to find: the parts claim a problem, the whole does not. A later
     * part withdrawing what an earlier one said is exactly the shape that makes the cheap unit
     * wrong, and it is invisible to everything except a comparison like this one.
     */
    @Test
    void fallsBackToTheCoarserUnitWhenAgreementIsBelowTheThreshold() {
        Composition.Agreement agreement = composition(0.9).over(
                List.of(CLEAR_1, CLEAR_2, TAIL_PROBLEM, HEAD_PROBLEM),
                noteStances(
                        CLEAR_1, "clear",
                        CLEAR_2, "clear",
                        TAIL_PROBLEM, PROBLEM,
                        HEAD_PROBLEM, "clear"),
                clauseStancesFor(CLEAR_1, CLEAR_2, TAIL_PROBLEM, HEAD_PROBLEM));

        assertEquals(0.75, agreement.rate());
        assertEquals(Composition.Unit.NOTE, agreement.unit());
        assertEquals(List.of(HEAD_PROBLEM), agreement.disagreed(),
                "the transcript has to name which one disagreed, not just how many");
    }

    /** The same measurement, a threshold that accepts it: the rate decides nothing on its own. */
    @Test
    void trustsTheFinerUnitWhenTheThresholdAcceptsThatRate() {
        Composition.Agreement agreement = composition(0.5).over(
                List.of(CLEAR_1, CLEAR_2, TAIL_PROBLEM, HEAD_PROBLEM),
                noteStances(
                        CLEAR_1, "clear",
                        CLEAR_2, "clear",
                        TAIL_PROBLEM, PROBLEM,
                        HEAD_PROBLEM, "clear"),
                clauseStancesFor(CLEAR_1, CLEAR_2, TAIL_PROBLEM, HEAD_PROBLEM));

        assertEquals(0.75, agreement.rate());
        assertEquals(Composition.Unit.CLAUSE, agreement.unit());
    }

    /**
     * A part nothing judged. Treated as absent it reads as "claims nothing", so a whole whose only
     * problem sat in the unjudged part composes to clear — the agreement rate then measures a gap
     * in the inputs and reports it as a property of the language.
     */
    @Test
    void refusesAPartNothingJudged() {
        Map<String, String> incomplete = clauseStancesFor(CLEAR_1);
        incomplete.remove("nothing of note");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                composition(0.9).over(
                        List.of(CLEAR_1), noteStances(CLEAR_1, "clear"), incomplete));

        assertTrue(thrown.getMessage().contains("nothing of note"), thrown::getMessage);
    }

    /** A sampled whole nothing judged: there is no coarse verdict to compare against. */
    @Test
    void refusesASampledWholeNothingJudged() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                composition(0.9).over(
                        List.of(CLEAR_1), Map.of(), clauseStancesFor(CLEAR_1)));

        assertTrue(thrown.getMessage().contains(CLEAR_1), thrown::getMessage);
    }
}
