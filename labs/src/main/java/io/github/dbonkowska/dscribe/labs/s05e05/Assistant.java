package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.labs.hub.HubResponse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reacts to each state the watcher reports: sets what the API allows, tells the operator what is
 * left, and gives the cue once the gate opens.
 *
 * <p>The stage is derived again from every state, never stored, so a restarted run carries on. What
 * is kept is what a single state cannot show:
 * <ul>
 *   <li>how stabilization went for each target, so a rejected reading gets one more try and no third
 *   <li>whether the fetch stage has been done in this process, so a charge still short after it stops
 *       the run rather than starting a second trip
 *   <li>what was last said, so the same thing is not printed on every phase step
 * </ul>
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

    /** Whoever does the manual side. Called only when what it is told changes. */
    interface Operator {

        /** What is still to change, in order. Rendered with the lesson's texts. */
        void say(List<NextSteps.Instruction> instructions);

        /** Start now, or wait for the phase. */
        void cue(Cue cue);

        /** The run's own word on where it is, such as a stage done. */
        void note(String note);
    }

    sealed interface Cue {
        /** Everything holds: start the jump. {@code last} for the stage after which the run is done. */
        record Go(boolean last) implements Cue {}

        /** Everything is set but the phase, which moves by itself. */
        record Wait(int wantPhase, int nowPhase) implements Cue {}
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
    private final GoGate gate;
    private final int stabilizationMin;
    private final int stabilizationMax;
    private final Supplier<LocalDate> today;
    private final Map<LocalDate, Attempts> attempts = new HashMap<>();
    private boolean fetched;
    private LocalDate lastTarget;
    private List<NextSteps.Instruction> lastSaid = List.of();
    private Object lastCue;
    private Result result;

    Assistant(Device device, Operator operator, Stage.Route route, LevelTable levels, SyncRatio ratio,
            GoGate gate, int stabilizationMin, int stabilizationMax, Supplier<LocalDate> today) {
        this.device = device;
        this.operator = operator;
        this.route = route;
        this.levels = levels;
        this.ratio = ratio;
        this.gate = gate;
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
        switch (route.of(state, today.get())) {
            case Stage.Stop stop -> result = new Result.Stop(stop.reason());
            case Stage.Arrived arrived -> result = new Result.Done();
            case Stage.Go go -> work(state, go);
        }
    }

    private void work(DeviceState state, Stage.Go go) {
        if (lastTarget != null && !lastTarget.equals(go.target()) && state.currentDate().equals(lastTarget)) {
            operator.note("Stage done: the device is at " + lastTarget + ". Next, " + go.kind() + " to "
                    + go.target() + ".");
        }
        lastTarget = go.target();
        if (go.kind() == Stage.Kind.RETURN) {
            fetched = true;
            if (state.batteryCharged() < 1) {
                result = new Result.Stop("The way back starts at " + state.batteryCharged() + "/"
                        + state.batteryCapacity() + " charge, so a jump could leave the device stranded.");
                return;
            }
        }
        if (go.kind() == Stage.Kind.FETCH && fetched) {
            result = new Result.Stop("Back from the fetch stage with " + state.batteryCharged() + "/"
                    + state.batteryCapacity() + " charge, still short of the tunnel. A second trip is not"
                    + " taken on a charge that could run out.");
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
        if (!steps.instructions().equals(lastSaid)) {
            lastSaid = steps.instructions();
            if (!lastSaid.isEmpty()) {
                operator.say(lastSaid);
            }
        }
        if (steps.instructions().isEmpty() && steps.writes().isEmpty()) {
            cue(state, go);
        } else {
            lastCue = null;
        }
    }

    /** The cue when the gate opens, the wait while only the phase is wrong, each said once. */
    private void cue(DeviceState state, Stage.Go go) {
        Object now = switch (gate.check(state, go.target().getYear())) {
            case GoGate.Open open -> new Cue.Go(go.kind() == Stage.Kind.TUNNEL);
            case GoGate.Shut shut when shut.reason() == GoGate.Reason.PHASE ->
                    new Cue.Wait(shut.wantPhase(), shut.nowPhase());
            case GoGate.Shut shut -> shut.reason();
        };
        if (Objects.equals(now, lastCue)) {
            return;
        }
        lastCue = now;
        switch (now) {
            case Cue cue -> operator.cue(cue);
            case GoGate.Reason reason -> operator.note(
                    "Everything is set, but the device does not allow a jump yet: " + reason + " does not hold.");
            default -> throw new IllegalStateException("Unexpected cue " + now);
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
