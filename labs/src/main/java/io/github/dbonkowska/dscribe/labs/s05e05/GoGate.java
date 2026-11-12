package io.github.dbonkowska.dscribe.labs.s05e05;

import java.util.List;

/**
 * Whether the operator may start the jump now. Pure, and the only place the cue is decided.
 *
 * <p>It opens only on every condition holding at once: a stable condition, active mode, some charge
 * left, the device in the target year's phase, and full flux. The phase rotates by itself every few
 * seconds, so it is checked after what the operator sets: waiting for it is worth saying only once
 * nothing else is in the way. Flux comes after the phase, because the device reads it below full
 * whenever the phase is wrong (first seen in a real run), and reported as flux, a wrong phase would
 * hide which phase to wait for.
 *
 * @param phaseBounds the highest year each phase but the last serves, strictly increasing
 */
record GoGate(List<Integer> phaseBounds) {

    /** Readiness the device reports when nothing is left to set, in percent. */
    static final int FULL_FLUX = 100;

    /** What keeps the gate shut, in the order it is checked. */
    enum Reason { CONDITION, MODE, BATTERY, PHASE, FLUX }

    sealed interface Result {}

    record Open() implements Result {}

    /**
     * @param reason    the first condition that does not hold
     * @param wantPhase the phase the target year is served in
     * @param nowPhase  the phase the device is in
     */
    record Shut(Reason reason, int wantPhase, int nowPhase) implements Result {}

    GoGate {
        phaseBounds = List.copyOf(phaseBounds);
    }

    /** 1 plus the number of bounds below {@code year}: a bound is the last year of its phase. */
    int phaseOf(int year) {
        return 1 + (int) phaseBounds.stream().filter(bound -> bound < year).count();
    }

    Result check(DeviceState state, int targetYear) {
        int want = phaseOf(targetYear);
        int now = state.internalMode();
        Reason reason;
        if (!DeviceState.STABLE.equals(state.condition())) {
            reason = Reason.CONDITION;
        } else if (!DeviceState.ACTIVE.equals(state.mode())) {
            reason = Reason.MODE;
        } else if (state.batteryCharged() < 1) {
            reason = Reason.BATTERY;
        } else if (now != want) {
            reason = Reason.PHASE;
        } else if (state.fluxDensity() != FULL_FLUX) {
            reason = Reason.FLUX;
        } else {
            return new Open();
        }
        return new Shut(reason, want, now);
    }
}
