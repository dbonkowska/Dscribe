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

    /** Five attempts, 1s doubling to a 5s ceiling — every step of the curve stays legible. */
    private static final RetryPolicy POLICY =
            new RetryPolicy(5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5));

    @Test
    void waitsTheInitialBackoffAfterTheFirstFailure() {
        assertEquals(Optional.of(Duration.ofSeconds(1)), POLICY.backoffAfter(1));
    }

    @Test
    void growsByTheMultiplierWithEachFailure() {
        assertEquals(Optional.of(Duration.ofSeconds(2)), POLICY.backoffAfter(2));
        assertEquals(Optional.of(Duration.ofSeconds(4)), POLICY.backoffAfter(3));
    }

    @Test
    void clampsToTheCeilingRatherThanDoublingPastIt() {
        // the curve would give 8s here
        assertEquals(Optional.of(Duration.ofSeconds(5)), POLICY.backoffAfter(4));
    }

    /** Empty means stop, not "wait zero" — it is the only thing that ends a retry loop. */
    @Test
    void hasNothingToOfferOnceEveryAttemptIsSpent() {
        assertEquals(Optional.empty(), POLICY.backoffAfter(5));
        assertEquals(Optional.empty(), POLICY.backoffAfter(6));
    }

    /**
     * The cap is what bounds a run against an API that fails on purpose. One that allowed a single
     * attempt would turn every simulated outage into a dead run.
     */
    @Test
    void defaultsSurviveAtLeastOneFailure() {
        assertTrue(RetryPolicy.defaults().maxAttempts() >= 2, "a single 503 must be survivable");
        assertEquals(
                Optional.of(RetryPolicy.defaults().initialBackoff()),
                RetryPolicy.defaults().backoffAfter(1));
    }
}
