package io.github.dbonkowska.dscribe.labs.s03e01;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the answer that costs nothing and has to be exactly right.
 *
 * <p>Whatever this pass misses, the judgement pass never sees — it only looks at records this one
 * found clean. So a rule that fails to fire does not produce a wrong answer anywhere visible; it
 * produces a submitted set quietly missing a category, against an oracle that replies with one
 * word. Every case below is a way for that to happen without anything looking broken.
 *
 * <p>Channels and bounds here are invented. Nothing in this file comes from the exercise.
 */
class RulesTest {

    private static final List<TaskParams.Channel> CHANNELS = List.of(
            new TaskParams.Channel("alpha", "alpha_units", 10.0, 20.0),
            new TaskParams.Channel("beta", "beta_units", 100.0, 200.0),
            new TaskParams.Channel("gamma", "gamma_units", 1.0, 5.0));

    private static final Rules RULES = new Rules(CHANNELS, "/");

    private static Reading reading(String type, double alpha, double beta, double gamma) {
        return new Reading(
                "0001",
                type,
                Map.of("alpha_units", alpha, "beta_units", beta, "gamma_units", gamma),
                "a remark");
    }

    @Test
    void findsNothingWrongWithARecordInsideItsBounds() {
        assertEquals(List.of(), RULES.violations(reading("alpha", 15.0, 0.0, 0.0)));
    }

    /** Both bounds are inclusive: a reading sitting exactly on one is in range, not out of it. */
    @Test
    void acceptsAValueOnEitherBound() {
        assertEquals(List.of(), RULES.violations(reading("alpha", 10.0, 0.0, 0.0)));
        assertEquals(List.of(), RULES.violations(reading("alpha", 20.0, 0.0, 0.0)));
    }

    @Test
    void flagsAnActiveChannelBelowItsMinimum() {
        assertEquals(
                List.of(new Rules.Violation("alpha", Rules.Kind.OUT_OF_RANGE)),
                RULES.violations(reading("alpha", 9.9, 0.0, 0.0)));
    }

    @Test
    void flagsAnActiveChannelAboveItsMaximum() {
        assertEquals(
                List.of(new Rules.Violation("alpha", Rules.Kind.OUT_OF_RANGE)),
                RULES.violations(reading("alpha", 20.1, 0.0, 0.0)));
    }

    /**
     * A channel the record does not claim to be reporting, reporting something anyway. Nothing
     * about the number is out of range — a plausible beta reading is only wrong because this
     * record said it was not measuring beta.
     */
    @Test
    void flagsAnInactiveChannelThatIsNotZero() {
        assertEquals(
                List.of(new Rules.Violation("beta", Rules.Kind.INACTIVE_NOT_ZERO)),
                RULES.violations(reading("alpha", 15.0, 150.0, 0.0)));
    }

    /**
     * Two channels active, and it is the <em>second</em> that is out of range. A loop returning
     * on its first verdict, or examining only the head of the channel list, passes every case
     * above and this one alone catches it.
     */
    @Test
    void checksEveryActiveChannelRatherThanTheFirst() {
        assertEquals(
                List.of(new Rules.Violation("beta", Rules.Kind.OUT_OF_RANGE)),
                RULES.violations(reading("alpha/beta", 15.0, 500.0, 0.0)));
    }

    /** Several at once, so a record is reported whole rather than one fault at a time. */
    @Test
    void reportsEveryViolationInOneRecord() {
        assertEquals(
                List.of(
                        new Rules.Violation("alpha", Rules.Kind.OUT_OF_RANGE),
                        new Rules.Violation("gamma", Rules.Kind.INACTIVE_NOT_ZERO)),
                RULES.violations(reading("alpha/beta", 99.0, 150.0, 3.0)));
    }

    /**
     * A name in the type field that the configuration does not define is a channel nobody will
     * ever range-check. Zero records carry one, so its appearance means the configuration is
     * wrong — the wrong separator, a misspelled name — and the run must stop rather than submit
     * a set that is silently short.
     */
    @Test
    void refusesATypeNamingAChannelTheConfigurationDoesNotDefine() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> RULES.violations(reading("alpha/delta", 15.0, 0.0, 0.0)));

        assertTrue(thrown.getMessage().contains("delta"),
                () -> "it has to name the channel it does not know: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("channels"),
                () -> "and the key that would define it: " + thrown.getMessage());
    }

    /**
     * A configured field the record does not carry. Left alone this is a null a few frames deeper,
     * in a loop over ten thousand records, naming neither the field nor the record.
     */
    @Test
    void refusesARecordMissingAConfiguredField() {
        Reading incomplete = new Reading(
                "0042", "alpha", Map.of("alpha_units", 15.0), "a remark");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> RULES.violations(incomplete));

        assertTrue(thrown.getMessage().contains("beta_units"),
                () -> "it has to name the missing field: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("0042"),
                () -> "and the record it was missing from: " + thrown.getMessage());
    }
}
