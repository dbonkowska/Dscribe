package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Nothing else in a run reads the device: every decision is a reaction to what this reports. A change
 * it misses leaves the operator waiting on a step already done, and one it invents repeats an
 * instruction. The flag is looked for in every reply, since nothing says which reply carries it.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class WatcherTest {

    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final Duration IDLE = Duration.ofSeconds(2);
    private static final Pattern FLAG = Pattern.compile("[{]F:[a-z]++[}]");

    private static final String STATE = """
            {"config": {"currentDate": "3001-04-05", "day": null, "month": null, "year": null,
             "syncRatio": 0, "stabilization": 0, "condition": "unstable", "fluxDensity": %d,
             "batteryStatus": "1/3", "PTA": false, "PTB": false, "PWR": 0, "mode": "standby",
             "internalMode": %d}%s}
            """;

    private static HubResponse state(int flux, int phase) {
        return state(flux, phase, "");
    }

    private static HubResponse state(int flux, int phase, String extra) {
        return new HubResponse(200, TestHeaders.of(Map.of()), STATE.formatted(flux, phase, extra));
    }

    /** A change the operator makes: unlike phase and flux, it never moves by itself. */
    private static HubResponse withPower(int level) {
        return new HubResponse(200, TestHeaders.of(Map.of()),
                STATE.formatted(0, 1, "").replace("\"PWR\": 0", "\"PWR\": " + level));
    }

    /** A clock that moves only when the watcher sleeps. */
    private static final class FakeClock extends Clock {
        private Instant now = Instant.parse("3000-01-01T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Replies handed out in order; running out means the watcher polled more than the test meant. */
    private static final class Script implements Supplier<HubResponse> {
        private final Deque<HubResponse> replies;
        int polls;

        Script(HubResponse... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public HubResponse get() {
            polls++;
            if (replies.isEmpty()) {
                fail("polled a " + polls + "th time, past the end of the script");
            }
            return replies.removeFirst();
        }
    }

    private final FakeClock clock = new FakeClock();
    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper sleeper = duration -> {
        sleeps.add(duration);
        clock.now = clock.now.plus(duration);
    };
    private final List<Watcher.Event> events = new ArrayList<>();

    private Watcher.Outcome watch(Script script, Function<Watcher.Event, Watcher.Verdict> listener) {
        Watcher watcher = new Watcher(script, sleeper, clock, INTERVAL, IDLE, FLAG);
        return watcher.run(event -> {
            events.add(event);
            return listener.apply(event);
        });
    }

    private Watcher.Outcome watch(Script script) {
        return watch(script, event -> Watcher.Verdict.WATCH);
    }

    @Test
    void reportsAnUnchangedStateOnceAndSleepsBetweenPolls() {
        Script script = new Script(state(0, 1), state(0, 1), state(0, 1));

        watch(script);

        assertEquals(1, events.size(), events::toString);
        assertInstanceOf(Watcher.Event.Changed.class, events.getFirst());
        assertEquals(List.of(INTERVAL, INTERVAL), sleeps);
    }

    @Test
    void namesTheFieldThatChanged() {
        Script script = new Script(state(0, 1), state(0, 2), state(0, 2), state(0, 2));

        watch(script);

        Watcher.Event.Changed second = assertInstanceOf(Watcher.Event.Changed.class, events.get(1));
        assertEquals(List.of("internalMode"), second.fields());
        assertEquals(2, second.state().internalMode());
    }

    @Test
    void endsIdleAfterNoChangeForTheLimitWithTheLastState() {
        HubResponse set = withPower(7);
        Script script = new Script(state(0, 1), set, set, set);

        Watcher.Outcome outcome = watch(script);

        Watcher.Outcome.Idle idle = assertInstanceOf(Watcher.Outcome.Idle.class, outcome);
        assertEquals(7, idle.last().pwr());
        assertEquals(4, script.polls);
    }

    /**
     * The phase rotates by itself, so a change in it alone is reported but is not activity: counted as
     * one, a device left on would never go idle.
     */
    @Test
    void reportsAPhaseChangeButStillEndsIdle() {
        Script script = new Script(state(0, 1), state(0, 2), state(0, 3));

        Watcher.Outcome outcome = watch(script);

        assertEquals(3, events.size(), events::toString);
        assertInstanceOf(Watcher.Outcome.Idle.class, outcome);
        assertEquals(3, script.polls);
    }

    /** Flux follows the phase, so it moves by itself too: a device left on would never go idle. */
    @Test
    void countsFluxFollowingThePhaseAsNoActivity() {
        Script script = new Script(state(100, 2), state(80, 3), state(80, 4));

        Watcher.Outcome outcome = watch(script);

        assertEquals(3, events.size(), events::toString);
        assertInstanceOf(Watcher.Outcome.Idle.class, outcome);
    }

    /** Nothing says which reply carries the flag, so an unchanged one is searched too. */
    @Test
    void reportsAFlagEvenInAnUnchangedState() {
        Script script = new Script(state(0, 1), state(0, 1, ", \"note\": \"{F:abc}\""), state(0, 1));

        watch(script);

        assertEquals(List.of(new Watcher.Event.Flagged("{F:abc}")),
                events.stream().filter(Watcher.Event.Flagged.class::isInstance).toList());
    }

    @Test
    void stopsPollingWhenTheListenerSaysSo() {
        Script script = new Script(state(0, 1), state(0, 2));

        Watcher.Outcome outcome = watch(script, event -> Watcher.Verdict.STOP);

        Watcher.Outcome.Stopped stopped = assertInstanceOf(Watcher.Outcome.Stopped.class, outcome);
        assertEquals(1, stopped.last().internalMode());
        assertEquals(1, script.polls);
        assertEquals(List.of(), sleeps);
    }

    /** Retrying belongs to the hub client below; here an unreadable reply ends the watch, quoted. */
    @Test
    void endsOnAnUnreadableReplyQuotingIt() {
        HubResponse junk = new HubResponse(200, TestHeaders.of(Map.of()), "<html>down</html>");
        Script script = new Script(state(0, 1), junk);

        Watcher.Outcome outcome = watch(script);

        Watcher.Outcome.Unreadable unreadable = assertInstanceOf(Watcher.Outcome.Unreadable.class, outcome);
        assertTrue(unreadable.reason().contains("<html>down</html>"), unreadable.reason());
        assertEquals(2, script.polls);
    }

    /** A flag in a reply that is not a state is still reported before the reply ends the watch. */
    @Test
    void reportsAFlagInAReplyThatIsNotAState() {
        HubResponse flagged = new HubResponse(200, TestHeaders.of(Map.of()), "{\"message\": \"{F:xyz}\"}");
        Script script = new Script(flagged);

        Watcher.Outcome outcome = watch(script);

        assertEquals(List.of(new Watcher.Event.Flagged("{F:xyz}")), events);
        assertInstanceOf(Watcher.Outcome.Unreadable.class, outcome);
    }
}
