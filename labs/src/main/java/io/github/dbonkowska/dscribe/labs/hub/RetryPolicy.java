package io.github.dbonkowska.dscribe.labs.hub;

import java.time.Duration;
import java.util.Optional;

/**
 * How long to wait before an attempt, and when to stop attempting.
 *
 * <p>Pure on purpose — no clock, no sleeping. The schedule is the part worth asserting, and a
 * test that had to elapse it would be slow and flaky for no gain; who does the waiting is
 * {@link Sleeper}'s problem.
 *
 * <p>No jitter. Jitter exists to spread a crowd of clients off a shared deadline, and there is
 * exactly one client here — it would buy nothing and make the schedule untestable as values.
 *
 * @param maxAttempts    total attempts, not retries. Beyond it there is no backoff, which is how
 *                       a caller learns to give up.
 * @param initialBackoff the curve's base, at attempt 1. Note that a caller never waits it: the
 *                       first attempt goes out immediately, so the first wait anyone actually
 *                       takes is the one before attempt 2.
 * @param maxBackoff     the ceiling the curve flattens at, so a long run cannot grow an
 *                       unbounded wait out of a modest multiplier
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

    /**
     * Six attempts, doubling from a second, flattening at thirty — around a minute of waiting
     * spread across a call before it gives up.
     *
     * <p>Tuned for an API that returns errors on purpose rather than one that is genuinely
     * broken: giving up early would turn a simulated outage into a dead run, and the exercise's
     * own advice is that the task rewards patience.
     */
    public static RetryPolicy defaults() {
        return new RetryPolicy(6, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30));
    }

    /**
     * The wait before attempt {@code attempt}, 1-based; empty when no such attempt will be made.
     *
     * <p>Computed in milliseconds because {@link Duration} has no multiplication by a
     * {@code double}. An exponent large enough to overflow the multiplication saturates to
     * {@link Long#MAX_VALUE} rather than wrapping, so it still clamps to {@code maxBackoff}.
     */
    public Optional<Duration> backoffBefore(int attempt) {
        if (attempt < 1 || attempt > maxAttempts) {
            return Optional.empty();
        }
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1);
        return Optional.of(Duration.ofMillis(Math.min((long) millis, maxBackoff.toMillis())));
    }
}
