package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.labs.s05e05.NextSteps.Instruction;
import io.github.dbonkowska.dscribe.labs.s05e05.NextSteps.Param;
import io.github.dbonkowska.dscribe.labs.s05e05.NextSteps.Side;
import io.github.dbonkowska.dscribe.labs.s05e05.NextSteps.Write;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What still has to change, worked out by comparing the state a stage wants with the state the
 * device shows. A wrong comparison either sends a write the device refuses while active, or tells
 * the operator to flip a control the wrong way on a jump that costs charge.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class NextStepsTest {

    private static final LocalDate NOW = LocalDate.of(3000, 1, 2);
    private static final LocalDate LATER = LocalDate.of(3100, 5, 6);
    private static final LocalDate EARLIER = LocalDate.of(2900, 7, 8);

    /** The day alone, so the ratio of a target is its day in hundredths. */
    private static final SyncRatio RATIO = new SyncRatio(1, 0, 0, 101);

    private static final int LEVEL = 7;

    private static final Stage.Go AHEAD = new Stage.Go(Stage.Kind.FETCH, LATER);
    private static final Stage.Go BACK = new Stage.Go(Stage.Kind.RETURN, EARLIER);
    private static final Stage.Go TUNNEL = new Stage.Go(Stage.Kind.TUNNEL, EARLIER);

    private static NextSteps steps(States states, Stage.Go stage) {
        return NextSteps.of(states.build(), stage, LEVEL, RATIO);
    }

    /** The API side as {@link #AHEAD} wants it, the device settled. */
    private static States settledAhead() {
        return States.at(NOW).target(LATER).syncRatio("0.06").stable();
    }

    @Test
    void asksForStandbyFirstWhenActiveAndAnApiValueIsWrong() {
        NextSteps steps = steps(States.at(NOW).active(), AHEAD);

        assertEquals(List.of(), steps.writes());
        assertEquals(List.of(new Instruction.Standby()), steps.instructions());
        assertFalse(steps.needsStabilization());
    }

    /** The ratio is validated against the date, so the date goes first. */
    @Test
    void writesTheDateThenTheRatio() {
        NextSteps steps = steps(States.at(NOW), AHEAD);

        assertEquals(List.of(
                        new Write(Param.DAY, 6),
                        new Write(Param.MONTH, 5),
                        new Write(Param.YEAR, 3100),
                        new Write(Param.SYNC_RATIO, new BigDecimal("0.06"))),
                steps.writes());
        assertEquals(List.of(), steps.instructions());
    }

    @Test
    void skipsAFieldThatAlreadyMatches() {
        NextSteps steps = steps(States.at(NOW).day(6).year(3100), AHEAD);

        assertEquals(List.of(
                        new Write(Param.MONTH, 5),
                        new Write(Param.SYNC_RATIO, new BigDecimal("0.06"))),
                steps.writes());
    }

    @Test
    void rewritesAStaleRatio() {
        NextSteps steps = steps(States.at(NOW).target(LATER).syncRatio("0.01"), AHEAD);

        assertEquals(List.of(new Write(Param.SYNC_RATIO, new BigDecimal("0.06"))), steps.writes());
    }

    /** The device may hold the ratio at another scale; the value is what it compares on. */
    @Test
    void readsARatioAtAnotherScaleAsTheSame() {
        NextSteps steps = steps(States.at(NOW).target(LATER).syncRatio("0.060").stable(), AHEAD);

        assertEquals(List.of(), steps.writes());
    }

    @Test
    void asksForStabilizationOnceTheDateAndRatioHold() {
        NextSteps steps = steps(States.at(NOW).target(LATER).syncRatio("0.06").unstable(), AHEAD);

        assertTrue(steps.needsStabilization());
        assertEquals(List.of(), steps.writes());
        assertEquals(List.of(), steps.instructions());
    }

    /** Stabilization is written over the API too, so it needs standby like the rest. */
    @Test
    void asksForStandbyBeforeStabilizingWhenActive() {
        NextSteps steps = steps(States.at(NOW).target(LATER).syncRatio("0.06").unstable().active(), AHEAD);

        assertFalse(steps.needsStabilization());
        assertEquals(List.of(new Instruction.Standby()), steps.instructions());
    }

    @Test
    void turnsOnTheFutureControlForATargetAhead() {
        NextSteps steps = steps(settledAhead().pta(true), AHEAD);

        assertEquals(List.of(
                        new Instruction.Switch(Side.PAST, false),
                        new Instruction.Switch(Side.FUTURE, true),
                        new Instruction.Level(LEVEL)),
                steps.instructions());
        assertEquals(List.of(), steps.writes());
    }

    @Test
    void turnsOnThePastControlForATargetBehind() {
        NextSteps steps = steps(
                States.at(NOW).target(EARLIER).syncRatio("0.08").stable().ptb(true).pwr(LEVEL), BACK);

        assertEquals(List.of(
                        new Instruction.Switch(Side.PAST, true),
                        new Instruction.Switch(Side.FUTURE, false)),
                steps.instructions());
    }

    @Test
    void turnsOnBothControlsForATunnel() {
        NextSteps steps = steps(States.at(NOW).target(EARLIER).syncRatio("0.08").stable(), TUNNEL);

        assertEquals(List.of(
                        new Instruction.Switch(Side.PAST, true),
                        new Instruction.Switch(Side.FUTURE, true),
                        new Instruction.Level(LEVEL)),
                steps.instructions());
    }

    /** Active closes the API, so it comes only once every other setting is in place. */
    @Test
    void asksForActiveLastOnceEverythingElseMatches() {
        NextSteps steps = steps(settledAhead().ptb(true).pwr(LEVEL), AHEAD);

        assertEquals(List.of(new Instruction.Active()), steps.instructions());
    }

    @Test
    void holdsBackActiveWhileAManualSettingDiffers() {
        NextSteps steps = steps(settledAhead().ptb(true), AHEAD);

        assertEquals(List.of(new Instruction.Level(LEVEL)), steps.instructions());
    }

    @Test
    void hasNothingLeftWhenEverythingMatches() {
        NextSteps steps = steps(settledAhead().ptb(true).pwr(LEVEL).active(), AHEAD);

        assertEquals(List.of(), steps.writes());
        assertEquals(List.of(), steps.instructions());
        assertFalse(steps.needsStabilization());
    }
}
