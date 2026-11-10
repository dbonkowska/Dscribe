package io.github.dbonkowska.dscribe.labs.s05e05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose mistakes surface only once a route has started, where a wrong date, ratio or phase costs
 * battery that cannot be recovered. Each is refused at binding, naming the key, before anything is
 * sent.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value: each test below takes one of them away or spoils it. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            configureAction=put
            getConfigAction=peek
            paramKey=what
            valueKey=to
            params.day=d
            params.month=m
            params.year=y
            params.syncRatio=ratio
            params.stabilization=steady
            sync.dayWeight=3
            sync.monthWeight=5
            sync.yearWeight=7
            sync.modulus=11
            phaseBounds.1=100
            phaseBounds.2=200
            phaseBounds.3=300
            fetchDate=1234-05-06
            meetingDate=0987-06-05
            tunnelBattery=2
            docsFile=notes.md
            zone=Europe/Lisbon
            stabilizationMin=0
            stabilizationMax=50
            labels.past=BACK
            labels.future=AHEAD
            say.standby=go idle
            say.active=go live
            say.switchOn=flip %s up
            say.switchOff=flip %s down
            say.level=dial %s
            say.go=now
            say.waitPhase=want %s have %s
            say.browser=look at the page
            """;

    private static TaskParams bind(String props) {
        return new JavaPropsMapper().readValue(props, TaskParams.class);
    }

    private static String refusal(String props) {
        return assertThrows(RuntimeException.class, () -> bind(props)).getMessage();
    }

    private static String without(String prefix) {
        return COMPLETE.lines()
                .filter(line -> !line.startsWith(prefix))
                .collect(Collectors.joining("\n"));
    }

    @Test
    void bindsACompleteFile() {
        TaskParams params = bind(COMPLETE);

        assertEquals("x-task", params.verifyTask());
        assertEquals("put", params.configureAction());
        assertEquals("peek", params.getConfigAction());
        assertEquals(new TaskParams.Params("d", "m", "y", "ratio", "steady"), params.params());
        assertEquals(new TaskParams.Sync(3, 5, 7, 11), params.sync());
        assertEquals(List.of(100, 200, 300), params.phaseBounds());
        assertEquals(LocalDate.of(1234, 5, 6), params.fetchDate());
        assertEquals(LocalDate.of(987, 6, 5), params.meetingDate());
        assertEquals(2, params.tunnelBattery());
        assertEquals(ZoneId.of("Europe/Lisbon"), params.zone());
        assertEquals(0, params.stabilizationMin());
        assertEquals(50, params.stabilizationMax());
        assertEquals(new TaskParams.Labels("BACK", "AHEAD"), params.labels());
        assertEquals("flip %s up", params.say().switchOn());
        assertEquals("want %s have %s", params.say().waitPhase());
    }

    @ParameterizedTest
    @ValueSource(strings = {"verifyTask=x-task", "configureAction=put", "getConfigAction=peek",
            "paramKey=what", "valueKey=to", "params.day=d", "params.month=m", "params.year=y",
            "params.syncRatio=ratio", "params.stabilization=steady", "docsFile=notes.md",
            "labels.past=BACK", "labels.future=AHEAD", "say.standby=go idle", "say.active=go live",
            "say.go=now", "say.browser=look at the page"})
    void refusesABlankKey(String line) {
        String key = line.substring(0, line.indexOf('='));
        String props = COMPLETE.replace(line, key + "=  ");

        assertTrue(refusal(props).contains(key), () -> refusal(props));
    }

    @ParameterizedTest
    @ValueSource(strings = {"params.", "sync.", "labels.", "say."})
    void refusesAMissingBlock(String prefix) {
        String block = prefix.substring(0, prefix.length() - 1);

        assertTrue(refusal(without(prefix)).contains(block), () -> refusal(without(prefix)));
    }

    /** Read as some other day, it would send the device somewhere nobody asked for. */
    @ParameterizedTest
    @ValueSource(strings = {"fetchDate", "meetingDate"})
    void refusesADateThatIsNotIso(String key) {
        String props = COMPLETE.replaceAll("(?m)^" + key + "=.*$", key + "=06/05/1234");

        assertTrue(refusal(props).contains(key), () -> refusal(props));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fetchDate", "meetingDate", "tunnelBattery", "zone", "stabilizationMin",
            "stabilizationMax", "sync.dayWeight", "sync.monthWeight", "sync.yearWeight", "sync.modulus"})
    void refusesAMissingValueRatherThanDefaultingIt(String key) {
        String props = without(key + "=");

        assertTrue(refusal(props).contains(key), () -> refusal(props));
    }

    /** Two decimal places hold 0.00 to 1.00, so a remainder above 100 has no value the device takes. */
    @ParameterizedTest
    @ValueSource(ints = {0, -3, 102})
    void refusesAModulusTwoPlacesCannotHold(int modulus) {
        String props = COMPLETE.replace("sync.modulus=11", "sync.modulus=" + modulus);

        String message = refusal(props);
        assertTrue(message.contains("sync.modulus") && message.contains(String.valueOf(modulus)), message);
    }

    @Test
    void acceptsTheLargestModulusTwoPlacesHold() {
        TaskParams params = bind(COMPLETE.replace("sync.modulus=11", "sync.modulus=101"));

        assertEquals(101, params.sync().modulus());
    }

    @Test
    void refusesATunnelThatNeedsNoBattery() {
        String props = COMPLETE.replace("tunnelBattery=2", "tunnelBattery=0");

        assertTrue(refusal(props).contains("tunnelBattery"), () -> refusal(props));
    }

    /** Phases are told apart by these bounds, so an out-of-order pair would put a year in two phases. */
    @ParameterizedTest
    @ValueSource(strings = {"phaseBounds.2=100", "phaseBounds.2=50"})
    void refusesPhaseBoundsThatDoNotStrictlyIncrease(String line) {
        String props = COMPLETE.replace("phaseBounds.2=200", line);

        String message = refusal(props);
        assertTrue(message.contains("phaseBounds"), message);
    }

    @Test
    void refusesMissingPhaseBounds() {
        assertTrue(refusal(without("phaseBounds")).contains("phaseBounds"), () -> refusal(without("phaseBounds")));
    }

    @Test
    void refusesAnUnknownZone() {
        String props = COMPLETE.replace("zone=Europe/Lisbon", "zone=Nowhere/Atall");

        assertTrue(refusal(props).contains("zone"), () -> refusal(props));
    }

    @Test
    void refusesAFlagPatternThatDoesNotCompile() {
        String props = COMPLETE.replace("flagPattern=[{]F[}]", "flagPattern=[");

        assertTrue(refusal(props).contains("flagPattern"), () -> refusal(props));
    }

    @Test
    void refusesAStabilizationRangeThatIsEmpty() {
        String props = COMPLETE.replace("stabilizationMin=0", "stabilizationMin=51");

        String message = refusal(props);
        assertTrue(message.contains("stabilizationMin") && message.contains("stabilizationMax"), message);
    }

    /**
     * Checked by what it renders, not by counting placeholders: {@code %1$s} counts as one slot and
     * {@code %%s} counts as one but renders none.
     */
    @ParameterizedTest
    @ValueSource(strings = {"say.switchOn=flip it up", "say.switchOn=flip %%s up", "say.switchOff=flip down",
            "say.level=dial it", "say.waitPhase=want %s", "say.waitPhase=want %1$s have %1$s"})
    void refusesAnOperatorTextMissingASlot(String line) {
        String key = line.substring(0, line.indexOf('='));
        String props = COMPLETE.replaceAll("(?m)^" + key.replace(".", "\\.") + "=.*$",
                java.util.regex.Matcher.quoteReplacement(line));

        String message = refusal(props);
        assertTrue(message.contains(key), message);
    }

    @Test
    void acceptsSlotsWrittenByPosition() {
        TaskParams params = bind(COMPLETE.replace("say.waitPhase=want %s have %s",
                "say.waitPhase=have %2$s want %1$s"));

        assertEquals("have %2$s want %1$s", params.say().waitPhase());
    }

    /** A text with no slot is rendered with no argument, so a stray {@code %d} would throw mid-run. */
    @Test
    void refusesAFixedTextThatCannotRender() {
        String props = COMPLETE.replace("say.go=now", "say.go=now %d");

        assertTrue(refusal(props).contains("say.go"), () -> refusal(props));
    }
}
