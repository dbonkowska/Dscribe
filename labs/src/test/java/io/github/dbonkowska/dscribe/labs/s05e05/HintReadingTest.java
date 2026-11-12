package io.github.dbonkowska.dscribe.labs.s05e05;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model only takes a free-text hint apart; code does the arithmetic. Narrowed by the schema so a
 * finished value cannot be represented, and checked again in code because a provider can ignore the
 * schema. A reading the model cannot make is {@code unclear}, which counts as a failed attempt rather
 * than a guess.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class HintReadingTest {

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private static Map<String, Object> reply(Object base, Object delta, String direction) {
        return Map.of("base", base, "delta", delta, "direction", direction);
    }

    @Test
    void requiresThePartsAndNothingElse() {
        JsonNode schema = HintReading.schema();

        assertEquals(List.of("base", "delta", "direction"), strings(schema.path("required")));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals("integer", schema.at("/properties/base/type").asString());
        assertEquals("integer", schema.at("/properties/delta/type").asString());
    }

    @Test
    void narrowsTheDirectionToThreeLabels() {
        JsonNode schema = HintReading.schema();

        assertEquals(List.of("add", "subtract", "unclear"), strings(schema.at("/properties/direction/enum")));
    }

    @Test
    void addsTheDelta() {
        assertEquals(new HintReading.Value.Of(425),
                HintReading.parse(reply(400, 25, "add")).value(0, 1000));
    }

    @Test
    void subtractsTheDelta() {
        assertEquals(new HintReading.Value.Of(375),
                HintReading.parse(reply(400, 25, "subtract")).value(0, 1000));
    }

    @Test
    void givesNoValueForAnUnclearReading() {
        HintReading.Value value = HintReading.parse(reply(400, 25, "unclear")).value(0, 1000);

        assertInstanceOf(HintReading.Value.None.class, value);
    }

    /** The device would refuse it, or take it and drift: either way it is a failed reading. */
    @Test
    void givesNoValueOutsideTheRangeAndNamesTheRange() {
        HintReading.Value value = HintReading.parse(reply(10, 20, "subtract")).value(0, 1000);

        HintReading.Value.None none = assertInstanceOf(HintReading.Value.None.class, value);
        assertTrue(none.reason().contains("-10") && none.reason().contains("0") && none.reason().contains("1000"),
                none.reason());
    }

    @Test
    void acceptsBothEndsOfTheRange() {
        assertEquals(new HintReading.Value.Of(0), HintReading.parse(reply(10, 10, "subtract")).value(0, 1000));
        assertEquals(new HintReading.Value.Of(1000), HintReading.parse(reply(990, 10, "add")).value(0, 1000));
    }

    @Test
    void refusesADirectionOutsideTheSet() {
        String message = assertThrows(IllegalStateException.class,
                () -> HintReading.parse(reply(400, 25, "multiply"))).getMessage();

        assertTrue(message.contains("multiply"), message);
    }

    @Test
    void refusesABaseThatIsNotAWholeNumber() {
        String word = assertThrows(IllegalStateException.class,
                () -> HintReading.parse(reply("four", 25, "add"))).getMessage();
        String fraction = assertThrows(IllegalStateException.class,
                () -> HintReading.parse(reply(12.5, 25, "add"))).getMessage();

        assertTrue(word.contains("base") && word.contains("four"), word);
        assertTrue(fraction.contains("base") && fraction.contains("12.5"), fraction);
    }

    /** A model can answer the JSON literal {@code null}, which reads as no reply at all. */
    @Test
    void refusesNoReply() {
        String message = assertThrows(IllegalStateException.class, () -> HintReading.parse(null)).getMessage();

        assertTrue(message.contains("no reply"), message);
    }

    @Test
    void refusesAMissingPart() {
        String message = assertThrows(IllegalStateException.class,
                () -> HintReading.parse(Map.of("base", 400, "direction", "add"))).getMessage();

        assertTrue(message.contains("delta"), message);
    }
}
