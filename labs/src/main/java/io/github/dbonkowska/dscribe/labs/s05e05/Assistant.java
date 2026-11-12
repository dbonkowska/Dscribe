package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.labs.hub.HubResponse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reacts to each state the watcher reports: sets what the API allows, and tells the operator what
 * is left.
 *
 * <p>The stage is derived again from every state, never stored, so a restarted run carries on. The
 * one thing kept is how stabilization went for each target: a reading the device rejects is followed
 * by one more of the same hint, and a second rejection stops the run rather than guessing a third
 * time.
 */
final class Assistant implements Watcher.Listener {

    /** Readings of one hint before the run stops. */
    static final int MAX_READINGS = 2;

    /** What the assistant does to the device, narrow so it can be driven from a script. */
    interface Device {

        /** Sets one value. A refusal comes back as the reply, not thrown. */
        HubResponse configure(NextSteps.Param param, Number value);

        /** Takes a hint apart. A reply the model could not give is an {@code unclear} reading. */
        HintReading label(String hint);
    }

    /** Whoever does the manual side. */
    interface Operator {
        void say(List<NextSteps.Instruction> instructions);
    }

    sealed interface Result {
        record Done() implements Result {}

        record Stop(String reason) implements Result {}
    }

    /** How stabilization went for one target. */
    private static final class Attempts {
        final List<String> readings = new ArrayList<>();
        Integer pending;
    }

    private final Device device;
    private final Operator operator;
    private final Stage.Route route;
    private final LevelTable levels;
    private final SyncRatio ratio;
    private final int stabilizationMin;
    private final int stabilizationMax;
    private final Supplier<LocalDate> today;
    private final Map<LocalDate, Attempts> attempts = new HashMap<>();
    private Result result;

    Assistant(Device device, Operator operator, Stage.Route route, LevelTable levels, SyncRatio ratio,
            int stabilizationMin, int stabilizationMax, Supplier<LocalDate> today) {
        this.device = device;
        this.operator = operator;
        this.route = route;
        this.levels = levels;
        this.ratio = ratio;
        this.stabilizationMin = stabilizationMin;
        this.stabilizationMax = stabilizationMax;
        this.today = today;
    }

    @Override
    public Watcher.Verdict on(Watcher.Event event) {
        if (event instanceof Watcher.Event.Changed changed) {
            react(changed.state());
        }
        return result == null ? Watcher.Verdict.WATCH : Watcher.Verdict.STOP;
    }

    /** How the run ended, once it has. */
    Optional<Result> result() {
        return Optional.ofNullable(result);
    }

    private void react(DeviceState state) {
        Stage stage = route.of(state, today.get());
        if (!(stage instanceof Stage.Go go)) {
            return;
        }
        NextSteps steps = NextSteps.of(state, go, levels.require(go.target().getYear()), ratio);
        for (NextSteps.Write write : steps.writes()) {
            if (!send(write.param(), write.value())) {
                return;
            }
        }
        if (steps.needsStabilization()) {
            stabilize(state, go.target());
            return;
        }
        if (!steps.instructions().isEmpty()) {
            operator.say(steps.instructions());
        }
    }

    /**
     * Reads the hint and sets the value it comes to. The device is the judge: unstable with the last
     * value set means that reading was wrong. A reading with no value is followed at once, since
     * nothing would change to prompt another.
     */
    private void stabilize(DeviceState state, LocalDate target) {
        Optional<String> hint = state.hint();
        if (hint.isEmpty()) {
            return;
        }
        Attempts tried = attempts.computeIfAbsent(target, date -> new Attempts());
        if (tried.pending != null) {
            if (state.stabilization() != tried.pending) {
                return;
            }
            tried.pending = null;
        }
        while (tried.readings.size() < MAX_READINGS) {
            HintReading.Value value = device.label(hint.get()).value(stabilizationMin, stabilizationMax);
            switch (value) {
                case HintReading.Value.Of of -> {
                    tried.readings.add(of.value() + ", which the device did not take as stable");
                    tried.pending = of.value();
                    send(NextSteps.Param.STABILIZATION, of.value());
                    return;
                }
                case HintReading.Value.None none -> tried.readings.add(none.reason());
            }
        }
        result = new Result.Stop(
                "Stabilization for " + target + " did not hold after " + MAX_READINGS + " readings of the hint \""
                        + hint.get() + "\": " + String.join("; ", tried.readings));
    }

    /** False, with the run stopped, when the device refused the value. */
    private boolean send(NextSteps.Param param, Number value) {
        Optional<String> refusal = DeviceState.refusal(device.configure(param, value));
        refusal.ifPresent(reason -> result = new Result.Stop("Setting " + param + " to " + value + ": " + reason));
        return refusal.isEmpty();
    }
}
