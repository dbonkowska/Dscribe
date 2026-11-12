package io.github.dbonkowska.dscribe.labs.s05e05;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What still has to change for a stage, from comparing the state it wants with the state the device
 * shows. Pure: nothing here is remembered, so a step clears when the state shows it done, and a wrong
 * click shows up as that step coming back.
 *
 * <p>The order follows the device:
 * <ol>
 *   <li>The API side is written only in standby. While active with an API value wrong, the one thing
 *       asked is standby.
 *   <li>Day, month and year go first, then the ratio, which the device checks against the date.
 *   <li>Stabilization follows once those hold. It needs the hint, so it is flagged here and done by the
 *       caller.
 *   <li>Only then the manual settings, listed together, and active last, once the rest matches.
 * </ol>
 *
 * @param writes             API values still to send, in order
 * @param needsStabilization the date and ratio hold, but the device does not read stable yet
 * @param instructions       what the operator still has to change, in order
 */
record NextSteps(List<NextSteps.Write> writes, boolean needsStabilization, List<NextSteps.Instruction> instructions) {

    /**
     * The values the API sets, each sent under a name the lesson binds. This works out all but
     * {@link #STABILIZATION}, which needs the hint and is set by the caller.
     */
    enum Param { DAY, MONTH, YEAR, SYNC_RATIO, STABILIZATION }

    /** Which direction control: the one for jumps back, or the one for jumps ahead. */
    enum Side { PAST, FUTURE }

    /** One API value to send. */
    record Write(Param param, Number value) {}

    /** One thing the operator has to change. Rendered with the lesson's texts by the caller. */
    sealed interface Instruction {
        record Standby() implements Instruction {}

        record Active() implements Instruction {}

        record Switch(Side side, boolean on) implements Instruction {}

        record Level(int level) implements Instruction {}
    }

    static NextSteps of(DeviceState state, Stage.Go stage, int level, SyncRatio ratio) {
        LocalDate target = stage.target();
        List<Write> writes = new ArrayList<>();
        differs(writes, Param.DAY, state.day(), target.getDayOfMonth());
        differs(writes, Param.MONTH, state.month(), target.getMonthValue());
        differs(writes, Param.YEAR, state.year(), target.getYear());
        BigDecimal wanted = ratio.of(target);
        if (state.syncRatio().compareTo(wanted) != 0) {
            writes.add(new Write(Param.SYNC_RATIO, wanted));
        }
        boolean stable = DeviceState.STABLE.equals(state.condition());
        boolean standby = DeviceState.STANDBY.equals(state.mode());

        if (!writes.isEmpty() || !stable) {
            if (!standby) {
                return new NextSteps(List.of(), false, List.of(new Instruction.Standby()));
            }
            return new NextSteps(List.copyOf(writes), writes.isEmpty(), List.of());
        }

        boolean tunnel = stage.kind() == Stage.Kind.TUNNEL;
        boolean back = target.isBefore(state.currentDate());
        List<Instruction> instructions = new ArrayList<>();
        boolean pastOn = tunnel || back;
        boolean futureOn = tunnel || !back;
        if (state.pta() != pastOn) {
            instructions.add(new Instruction.Switch(Side.PAST, pastOn));
        }
        if (state.ptb() != futureOn) {
            instructions.add(new Instruction.Switch(Side.FUTURE, futureOn));
        }
        if (state.pwr() != level) {
            instructions.add(new Instruction.Level(level));
        }
        if (instructions.isEmpty() && !DeviceState.ACTIVE.equals(state.mode())) {
            instructions.add(new Instruction.Active());
        }
        return new NextSteps(List.of(), false, List.copyOf(instructions));
    }

    private static void differs(List<Write> writes, Param param, Integer held, int wanted) {
        if (!Objects.equals(held, wanted)) {
            writes.add(new Write(param, wanted));
        }
    }
}
