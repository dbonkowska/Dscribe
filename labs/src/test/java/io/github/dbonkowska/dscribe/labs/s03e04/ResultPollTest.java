package io.github.dbonkowska.dscribe.labs.s03e04;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hub answers a check that is not ready yet with an ordinary success status and a body saying
 * so, so the only thing that says a result is in is what the body holds. A poll that stops on the
 * status stops on the first check, records "not yet" as the outcome, and never reads the result.
 *
 * <p>Bodies here are invented. The sleeper records what it was asked for and returns at once, so
 * the schedule is asserted as values.
 */
class ResultPollTest {

    private static final Pattern FLAG = Pattern.compile("[{]F[}]");
    private static final Duration INTERVAL = Duration.ofSeconds(10);

    private final List<Duration> waits = new ArrayList<>();
    private int checks;

    private ResultPoll poll(int attempts, String... bodies) {
        Deque<String> queued = new ArrayDeque<>(List.of(bodies));
        return new ResultPoll(
                () -> {
                    checks++;
                    return queued.poll();
                },
                FLAG, waits::add, attempts, INTERVAL);
    }

    @Test
    void keepsCheckingPastReplyThatSaysItIsNotReadyAndReturnsTheResult() {
        ResultPoll poll = poll(5, "not ready yet", "still not ready", "done {F} here");

        Optional<String> result = poll.run();

        assertEquals(Optional.of("{F}"), result);
        assertEquals(3, checks, "it stops on the check that held the result, not before");
        assertEquals(List.of(INTERVAL, INTERVAL, INTERVAL), waits, "and waits before every check");
    }

    @Test
    void stopsAtOnceOnAResultAndChecksNoMore() {
        ResultPoll poll = poll(5, "{F}", "never asked for");

        assertEquals(Optional.of("{F}"), poll.run());
        assertEquals(1, checks);
    }

    @Test
    void givesUpAfterItsLastAttemptWithNothing() {
        ResultPoll poll = poll(3, "not yet", "not yet", "not yet", "would be a fourth");

        assertEquals(Optional.empty(), poll.run());
        assertEquals(3, checks, "exhaustion is the only other way out");
        assertEquals(List.of(INTERVAL, INTERVAL, INTERVAL), waits);
    }
}
