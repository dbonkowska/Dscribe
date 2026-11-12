package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.s05e05.HintReading.Direction;
import io.github.dbonkowska.dscribe.labs.s05e05.NextSteps.Instruction;
import io.github.dbonkowska.dscribe.labs.s05e05.NextSteps.Param;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The assistant is the only thing in a run that writes to the device, and it does so on its own
 * reading of each state. A write sent while active is refused, a stabilization taken from a misread
 * hint drifts the jump, and a third guess at the same hint is a guess. Fed states directly, as the
 * watcher would emit them, with a scripted device that records the order of its calls.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class AssistantTest {

    private static final LocalDate TODAY = LocalDate.of(3000, 1, 2);
    private static final LocalDate FETCH = LocalDate.of(3100, 5, 6);
    private static final LocalDate MEETING = LocalDate.of(2900, 7, 8);

    private static final Stage.Route ROUTE = new Stage.Route(FETCH, MEETING, 2);

    /** The day alone, so the ratio of the fetch date is 0.06. */
    private static final SyncRatio RATIO = new SyncRatio(1, 0, 0, 101);

    private static final LevelTable LEVELS = LevelTable.parse("""
            | Y | L | Y | L | Y | L |
            | --- | --- | --- | --- | --- | --- |
            | 2900 | 4 | 3000 | 5 | 3100 | 6 |
            """);

    private static final String HINT = "some free text";

    /** At today, short of charge, so on the way to the fetch date, with the API side right. */
    private static States towardFetch() {
        return States.at(TODAY).battery(1).target(FETCH).syncRatio("0.06").hint(HINT);
    }

    /** A device that records what it was asked, answering from a script. */
    private static final class Script implements Assistant.Device {
        final List<String> calls = new ArrayList<>();
        private final Deque<HintReading> readings = new ArrayDeque<>();
        private HubResponse configured = reply(200, "{}");

        Script readings(HintReading... next) {
            readings.addAll(List.of(next));
            return this;
        }

        Script refuseConfigure(String body) {
            configured = reply(400, body);
            return this;
        }

        @Override
        public HubResponse configure(Param param, Number value) {
            calls.add("configure " + param + " " + value);
            return configured;
        }

        @Override
        public HintReading label(String hint) {
            calls.add("label " + hint);
            if (readings.isEmpty()) {
                fail("labelled a " + (calls.stream().filter(c -> c.startsWith("label")).count())
                        + "th time, past the end of the script");
            }
            return readings.removeFirst();
        }

        private static HubResponse reply(int status, String body) {
            return new HubResponse(status, TestHeaders.of(Map.of()), body);
        }
    }

    private static final class Console implements Assistant.Operator {
        final List<List<Instruction>> said = new ArrayList<>();

        @Override
        public void say(List<Instruction> instructions) {
            said.add(instructions);
        }
    }

    private final Console operator = new Console();

    private Assistant assistant(Script device) {
        return new Assistant(device, operator, ROUTE, LEVELS, RATIO, 0, 1000, () -> TODAY);
    }

    private static Watcher.Verdict feed(Assistant assistant, States states) {
        DeviceState state = states.build();
        return assistant.on(new Watcher.Event.Changed(state, List.of()));
    }

    @Test
    void writesWhatDiffersInOrder() {
        Script device = new Script();

        Watcher.Verdict verdict = feed(assistant(device), States.at(TODAY).battery(1));

        assertEquals(Watcher.Verdict.WATCH, verdict);
        assertEquals(List.of(
                "configure DAY 6", "configure MONTH 5", "configure YEAR 3100", "configure SYNC_RATIO 0.06"),
                device.calls);
    }

    @Test
    void writesNothingWhileActive() {
        Script device = new Script();

        feed(assistant(device), States.at(TODAY).battery(1).active());

        assertEquals(List.of(), device.calls);
        assertEquals(List.of(List.of(new Instruction.Standby())), operator.said);
    }

    @Test
    void labelsTheHintOnceAndSetsTheComputedValue() {
        Script device = new Script().readings(new HintReading(400, 25, Direction.ADD));

        feed(assistant(device), towardFetch());

        assertEquals(List.of("label " + HINT, "configure STABILIZATION 425"), device.calls);
    }

    /** The device judges the value: unstable with it set means that reading was wrong. */
    @Test
    void labelsOnceMoreWhenTheDeviceStaysUnstable() {
        Script device = new Script().readings(
                new HintReading(400, 25, Direction.ADD), new HintReading(400, 25, Direction.SUBTRACT));
        Assistant assistant = assistant(device);

        feed(assistant, towardFetch());
        feed(assistant, towardFetch().stabilization(425));

        assertEquals(List.of(
                        "label " + HINT, "configure STABILIZATION 425",
                        "label " + HINT, "configure STABILIZATION 375"),
                device.calls);
    }

    @Test
    void stopsAfterTwoReadingsFailQuotingTheHintAndBothValues() {
        Script device = new Script().readings(
                new HintReading(400, 25, Direction.ADD), new HintReading(400, 25, Direction.SUBTRACT));
        Assistant assistant = assistant(device);

        feed(assistant, towardFetch());
        feed(assistant, towardFetch().stabilization(425));
        Watcher.Verdict verdict = feed(assistant, towardFetch().stabilization(375));

        assertEquals(Watcher.Verdict.STOP, verdict);
        Assistant.Result.Stop stop = assertInstanceOf(Assistant.Result.Stop.class, assistant.result().orElseThrow());
        assertTrue(stop.reason().contains(HINT) && stop.reason().contains("425") && stop.reason().contains("375"),
                stop.reason());
        assertEquals(4, device.calls.size(), device.calls::toString);
    }

    /** Nothing would change to prompt another try, so a failed reading is followed at once. */
    @Test
    void countsAnUnclearReadingAsAFailedAttempt() {
        Script device = new Script().readings(
                new HintReading(0, 0, Direction.UNCLEAR), new HintReading(400, 25, Direction.ADD));

        feed(assistant(device), towardFetch());

        assertEquals(List.of("label " + HINT, "label " + HINT, "configure STABILIZATION 425"), device.calls);
    }

    @Test
    void stopsOnTwoReadingsWithNoValueWithoutWriting() {
        Script device = new Script().readings(
                new HintReading(0, 0, Direction.UNCLEAR), new HintReading(10, 20, Direction.SUBTRACT));
        Assistant assistant = assistant(device);

        Watcher.Verdict verdict = feed(assistant, towardFetch());

        assertEquals(Watcher.Verdict.STOP, verdict);
        assertEquals(List.of("label " + HINT, "label " + HINT), device.calls);
        Assistant.Result.Stop stop = assertInstanceOf(Assistant.Result.Stop.class, assistant.result().orElseThrow());
        assertTrue(stop.reason().contains("-10"), stop.reason());
    }

    @Test
    void stopsOnARefusedWriteQuotingTheDevice() {
        Script device = new Script().refuseConfigure("{\"code\": -42, \"message\": \"Not that value.\"}");
        Assistant assistant = assistant(device);

        Watcher.Verdict verdict = feed(assistant, States.at(TODAY).battery(1));

        assertEquals(Watcher.Verdict.STOP, verdict);
        assertEquals(List.of("configure DAY 6"), device.calls);
        Assistant.Result.Stop stop = assertInstanceOf(Assistant.Result.Stop.class, assistant.result().orElseThrow());
        assertTrue(stop.reason().contains("-42") && stop.reason().contains("Not that value."), stop.reason());
    }
}
