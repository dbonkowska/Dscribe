package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls the device, and turns the replies into change events. Nothing else in a run reads it, and no
 * model ever sees a poll.
 *
 * <p>Knows nothing about stages: it reports what changed and leaves deciding to the listener. Every
 * reply is searched for the flag before it is read as a state, since nothing says which reply carries
 * it. A reply that is not a state ends the watch, quoted. Retrying a failed call is the hub client's
 * job, below this. The watch ends idle once nothing but the phase and the flux that follows it has
 * changed for the idle limit.
 */
final class Watcher {

    private static final RecordComponent[] FIELDS = DeviceState.class.getRecordComponents();

    /**
     * The fields that move by themselves: the phase rotates, and flux drops below full whenever the
     * phase is wrong. A change in them is reported, since the cue depends on both, but it does not
     * count as activity: counted, a device left on would never go idle.
     */
    private static final Set<String> SELF_MOVING = Set.of("internalMode", "fluxDensity");

    /** What the listener wants after an event. */
    enum Verdict { WATCH, STOP }

    interface Listener {
        Verdict on(Event event);
    }

    sealed interface Event {
        /**
         * The device shows a state different from the last one.
         *
         * @param fields which of {@link DeviceState}'s fields differ; every one for the first state
         */
        record Changed(DeviceState state, List<String> fields) implements Event {}

        /** A reply matched the flag pattern. */
        record Flagged(String match) implements Event {}
    }

    sealed interface Outcome {
        /** The listener said stop. {@code last} is null if no state had been read. */
        record Stopped(DeviceState last) implements Outcome {}

        /** Nothing changed for the idle limit. */
        record Idle(DeviceState last) implements Outcome {}

        /** A reply that is not a state. */
        record Unreadable(String reason) implements Outcome {}
    }

    private final Supplier<HubResponse> poll;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Duration interval;
    private final Duration idleLimit;
    private final Pattern flag;

    Watcher(Supplier<HubResponse> poll, Sleeper sleeper, Clock clock, Duration interval, Duration idleLimit,
            Pattern flag) {
        this.poll = poll;
        this.sleeper = sleeper;
        this.clock = clock;
        this.interval = interval;
        this.idleLimit = idleLimit;
        this.flag = flag;
    }

    Outcome run(Listener listener) {
        DeviceState last = null;
        Instant changedAt = clock.instant();
        while (true) {
            HubResponse reply = poll.get();
            Matcher matcher = flag.matcher(reply.body());
            if (matcher.find() && listener.on(new Event.Flagged(matcher.group())) == Verdict.STOP) {
                return new Outcome.Stopped(last);
            }
            DeviceState state;
            try {
                state = DeviceState.of(reply);
            } catch (IllegalStateException e) {
                return new Outcome.Unreadable(e.getMessage());
            }
            List<String> changed = diff(last, state);
            last = state;
            if (changed.stream().anyMatch(field -> !SELF_MOVING.contains(field))) {
                changedAt = clock.instant();
            }
            if (!changed.isEmpty() && listener.on(new Event.Changed(state, changed)) == Verdict.STOP) {
                return new Outcome.Stopped(last);
            }
            if (Duration.between(changedAt, clock.instant()).compareTo(idleLimit) >= 0) {
                return new Outcome.Idle(last);
            }
            sleeper.await(interval);
        }
    }

    /** Field by field, so an event says what moved; every field for the first state. */
    private static List<String> diff(DeviceState before, DeviceState after) {
        if (before == null) {
            return Arrays.stream(FIELDS).map(RecordComponent::getName).toList();
        }
        List<String> changed = new ArrayList<>();
        for (RecordComponent field : FIELDS) {
            if (!Objects.equals(read(field, before), read(field, after))) {
                changed.add(field.getName());
            }
        }
        return List.copyOf(changed);
    }

    private static Object read(RecordComponent field, DeviceState state) {
        try {
            return field.getAccessor().invoke(state);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + field.getName() + " off a device state.", e);
        }
    }
}
