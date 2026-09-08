package io.github.dbonkowska.dscribe.labs.hub;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wrong schedule fails silently in both directions, and neither looks like a bug. Too short and
 * a rate-limited API is hammered into the long block its own documentation warns about; too long
 * and a run sits for minutes that read as the API being slow. Nothing throws either way.
 *
 * <p>Asserted as values rather than as elapsed time: the schedule is pure, so a test of it should
 * never sleep.
 */
class RetryPolicyTest {

    /** Small and exact, so every step of the curve is legible: 1s, 2s, 4s, then the 5s ceiling. */
    private static final RetryPolicy POLICY =
            new RetryPolicy(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5));

    @Test
    void startsAtTheInitialBackoff() {
        assertEquals(Optional.of(Duration.ofSeconds(1)), POLICY.backoffBefore(1));
    }

    @Test
    void growsByTheMultiplierOnEachAttempt() {
        assertEquals(Optional.of(Duration.ofSeconds(2)), POLICY.backoffBefore(2));
        assertEquals(Optional.of(Duration.ofSeconds(4)), POLICY.backoffBefore(3));
    }

    @Test
    void clampsToTheCeilingRatherThanDoublingPastIt() {
        // the curve would give 8s here
        assertEquals(Optional.of(Duration.ofSeconds(5)), POLICY.backoffBefore(4));
    }

    @Test
    void hasNothingToOfferPastTheAttemptCap() {
        assertEquals(Optional.empty(), POLICY.backoffBefore(5));
        assertEquals(Optional.empty(), POLICY.backoffBefore(6));
    }

    /**
     * The cap is what bounds a run against an API that fails on purpose. One that allowed a single
     * attempt would turn every simulated outage into a dead run.
     */
    @Test
    void defaultsSurviveAtLeastOneFailure() {
        assertTrue(RetryPolicy.defaults().maxAttempts() >= 2, "a single 503 must be survivable");
    }
}
