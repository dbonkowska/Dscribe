package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.labs.s05e05.GoGate.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The cue to start a jump is given only when this opens, and a jump started on a wrong reading costs
 * charge for nothing. So it opens on every condition holding at once, and each one shuts it on its own.
 * The phase rotates by itself, which is why its boundaries are pinned too.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class GoGateTest {

    private static final GoGate GATE = new GoGate(List.of(100, 200, 300));

    /** A target in phase 2. */
    private static final int TARGET_YEAR = 150;

    private static States ready() {
        return States.at(LocalDate.of(3000, 1, 2)).flux(100).stable().active().battery(1).phase(2);
    }

    private static GoGate.Shut shut(States states) {
        return assertInstanceOf(GoGate.Shut.class, GATE.check(states.build(), TARGET_YEAR));
    }

    @Test
    void opensWhenEverythingHolds() {
        assertInstanceOf(GoGate.Open.class, GATE.check(ready().build(), TARGET_YEAR));
    }

    @Test
    void shutsBelowFullFlux() {
        assertEquals(Reason.FLUX, shut(ready().flux(99)).reason());
    }

    @Test
    void shutsWhileUnstable() {
        assertEquals(Reason.CONDITION, shut(ready().unstable()).reason());
    }

    @Test
    void shutsInStandby() {
        assertEquals(Reason.MODE, shut(ready().standby()).reason());
    }

    @Test
    void shutsOnAnEmptyBattery() {
        assertEquals(Reason.BATTERY, shut(ready().battery(0)).reason());
    }

    /** The one that clears by itself, so the wait names which phase is wanted and which is now. */
    @Test
    void shutsInTheWrongPhaseNamingBoth() {
        GoGate.Shut shut = shut(ready().phase(3));

        assertEquals(new GoGate.Shut(Reason.PHASE, 2, 3), shut);
    }

    /**
     * Out of phase the device reads flux below full, whatever else is set, so a wrong phase is the
     * reason given: reported as flux, the operator would not learn which phase to wait for.
     */
    @Test
    void reportsTheWrongPhaseRatherThanTheFluxItLowers() {
        assertEquals(new GoGate.Shut(Reason.PHASE, 2, 3), shut(ready().phase(3).flux(80)));
    }

    /** Waiting for a phase is only worth saying once nothing else is in the way. */
    @Test
    void reportsAnotherReasonBeforeThePhase() {
        assertEquals(Reason.MODE, shut(ready().standby().phase(3)).reason());
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "100, 1", "101, 2", "200, 2", "201, 3", "300, 3", "301, 4", "9999, 4"})
    void placesAYearInItsPhase(int year, int phase) {
        assertEquals(phase, GATE.phaseOf(year));
    }
}
