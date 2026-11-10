package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every decision in a run is taken from this reading, so a field misread as a default would steer
 * the route with nothing to show it: a missing switch read as off, a charge read as empty. A field
 * that is absent or of the wrong type refuses the whole reading instead.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise. The field names are
 * the device's, read off its replies.
 */
class DeviceStateTest {

    private static final String FULL = """
            {
                "code": 11,
                "message": "ok",
                "config": {
                    "currentDate": "3001-04-05",
                    "day": 6,
                    "month": 7,
                    "year": 3002,
                    "syncRatio": 0.42,
                    "stabilization": 123,
                    "condition": "steady-ish",
                    "fluxDensity": 60,
                    "batteryStatus": "2\\/3",
                    "PTA": true,
                    "PTB": false,
                    "PWR": 17,
                    "mode": "idle",
                    "internalMode": 3
                },
                "needConfig": "some free text"
            }
            """;

    private static HubResponse ok(String body) {
        return new HubResponse(200, TestHeaders.of(Map.of()), body);
    }

    private static String refusal(HubResponse response) {
        return assertThrows(IllegalStateException.class, () -> DeviceState.of(response)).getMessage();
    }

    @Test
    void bindsEveryField() {
        DeviceState state = DeviceState.of(ok(FULL));

        assertEquals(LocalDate.of(3001, 4, 5), state.currentDate());
        assertEquals(Optional.of(LocalDate.of(3002, 7, 6)), state.date());
        assertEquals(new BigDecimal("0.42"), state.syncRatio());
        assertEquals(123, state.stabilization());
        assertEquals("steady-ish", state.condition());
        assertEquals(60, state.fluxDensity());
        assertEquals(2, state.batteryCharged());
        assertTrue(state.pta());
        assertFalse(state.ptb());
        assertEquals(17, state.pwr());
        assertEquals("idle", state.mode());
        assertEquals(3, state.internalMode());
        assertEquals(Optional.of("some free text"), state.hint());
    }

    /** A partly set date is not a target: the ratio and the hint are both taken against a whole one. */
    @ParameterizedTest
    @ValueSource(strings = {"\"day\": 6", "\"month\": 7", "\"year\": 3002"})
    void readsAPartlySetDateAsNoTarget(String field) {
        String name = field.substring(0, field.indexOf(':'));
        DeviceState state = DeviceState.of(ok(FULL.replace(field, name + ": null")));

        assertEquals(Optional.empty(), state.date());
    }

    @Test
    void readsAnAbsentHintAsNone() {
        DeviceState state = DeviceState.of(ok(FULL.replace(",\n    \"needConfig\": \"some free text\"", "")));

        assertEquals(Optional.empty(), state.hint());
    }

    /** Two readings of the same state compare equal, which is what a watcher diffs on. */
    @Test
    void comparesEqualForTheSameReply() {
        assertEquals(DeviceState.of(ok(FULL)), DeviceState.of(ok(FULL)));
    }

    @Test
    void refusesAMissingField() {
        String message = refusal(ok(FULL.replace("\"PTA\": true,", "")));

        assertTrue(message.contains("PTA"), message);
    }

    @Test
    void refusesAFieldOfTheWrongType() {
        String message = refusal(ok(FULL.replace("\"fluxDensity\": 60", "\"fluxDensity\": \"60\"")));

        assertTrue(message.contains("fluxDensity"), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x\\/3", "2", "2\\/", ""})
    void refusesAChargeThatIsNotAFraction(String charge) {
        String message = refusal(ok(FULL.replace("2\\/3", charge)));

        assertTrue(message.contains("batteryStatus"), message);
    }

    @Test
    void refusesACurrentDateThatIsNotIso() {
        String message = refusal(ok(FULL.replace("3001-04-05", "05.04.3001")));

        assertTrue(message.contains("currentDate") && message.contains("05.04.3001"), message);
    }

    @Test
    void refusesAReplyWithNoState() {
        String message = refusal(ok("{\"code\": 11, \"message\": \"ok\"}"));

        assertTrue(message.contains("config"), message);
    }

    @Test
    void refusesABodyThatIsNotJson() {
        String message = refusal(ok("<html>down</html>"));

        assertTrue(message.contains("<html>down</html>"), message);
    }

    /** A refused write is not a state: reading it as one would lose the device's reason. */
    @Test
    void refusesARejectedCallQuotingTheDevice() {
        HubResponse rejected = new HubResponse(400, TestHeaders.of(Map.of()),
                "{\"code\": -42, \"message\": \"Value out of its range.\"}");

        String message = refusal(rejected);
        assertTrue(message.contains("-42") && message.contains("Value out of its range."), message);
    }
}
