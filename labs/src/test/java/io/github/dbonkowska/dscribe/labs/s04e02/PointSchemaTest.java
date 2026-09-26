package io.github.dbonkowska.dscribe.labs.s04e02;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model answers inside the service window, and its answer is signed and stored before the hub
 * checks anything. A schema built from the wrong field list, or an answer let through with a field
 * missing, turns into signatures that never match and a timeout that names nothing — so both the
 * schema and the check on the answer are asserted here, before any window is spent on them.
 *
 * <p>Field names and the help reply's shape are invented.
 */
class PointSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Signing needs day, time, x, y; storing needs day, time, y, mode and the signature. */
    private static final JsonNode HELP = json("""
            {"verbs": {
                "sign":  {"needs": ["day", "time", "x", "y"]},
                "store": {"needs": ["day", "time", "y", "mode", "seal"]}}}
            """);

    private static final PointSchema SCHEMA = PointSchema.from(
            HELP, "/verbs/sign/needs", "/verbs/store/needs", "seal",
            List.of("day", "time"), Map.of("mode", List.of("on", "off")));

    @Test
    void takesTheUnionOfBothListsWithoutTheSignature() {
        assertEquals(List.of("day", "time", "x", "y", "mode"), SCHEMA.fields());
        assertEquals(List.of("day", "time", "x", "y"), SCHEMA.signFields());
        // the slot fields key a batch entry, and the signature is added by the run, not the model
        assertEquals(List.of("y", "mode"), SCHEMA.entryFields());
    }

    @Test
    void buildsAStrictSchemaOfStringFields() {
        JsonNode schema = SCHEMA.schema();

        assertEquals(List.of("points"), strings(schema.path("required")));
        assertEquals(false, schema.path("additionalProperties").asBoolean(true));
        JsonNode point = schema.path("properties").path("points").path("items");
        assertEquals("array", schema.path("properties").path("points").path("type").asString());
        assertEquals(List.of("day", "time", "x", "y", "mode"), strings(point.path("required")));
        assertEquals(false, point.path("additionalProperties").asBoolean(true));
        assertEquals("string", point.path("properties").path("x").path("type").asString());
        assertEquals(List.of("on", "off"), strings(point.path("properties").path("mode").path("enum")));
        assertTrue(point.path("properties").path("x").path("enum").isMissingNode());
    }

    @Test
    void refusesAHelpReplyWithoutTheList() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> PointSchema.from(
                HELP, "/verbs/sign/wants", "/verbs/store/needs", "seal", List.of("day"), Map.of()));

        assertTrue(thrown.getMessage().contains("/verbs/sign/wants"), thrown::getMessage);
    }

    /** A slot field no point has would file every entry under a key with a gap in it. */
    @Test
    void refusesASlotFieldThePointsDoNotHave() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> PointSchema.from(
                HELP, "/verbs/sign/needs", "/verbs/store/needs", "seal", List.of("date"), Map.of()));

        assertTrue(thrown.getMessage().contains("date"), thrown::getMessage);
    }

    /** A vocabulary for a field the points do not have is a typo, and would narrow nothing. */
    @Test
    void refusesAnEnumForAFieldThePointsDoNotHave() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> PointSchema.from(
                HELP, "/verbs/sign/needs", "/verbs/store/needs", "seal", List.of("day"),
                Map.of("mood", List.of("on"))));

        assertTrue(thrown.getMessage().contains("mood"), thrown::getMessage);
    }

    @Test
    void acceptsAWellFormedAnswer() {
        List<Map<String, String>> points = SCHEMA.validate(json("""
                {"points": [
                    {"day": "d1", "time": "t1", "x": "3", "y": "0", "mode": "on"},
                    {"day": "d1", "time": "t2", "x": "9", "y": "1", "mode": "off"}]}
                """));

        assertEquals(2, points.size());
        assertEquals("t2", points.get(1).get("time"));
        assertEquals("off", points.get(1).get("mode"));
    }

    @Test
    void refusesAnAnswerWithNoPoints() {
        assertRefused("{\"points\": []}", "no points");
        assertRefused("{}", "no points");
    }

    @Test
    void refusesAPointMissingAField() {
        assertRefused("""
                {"points": [{"day": "d1", "time": "t1", "x": "3", "mode": "on"}]}
                """, "point 1", "y");
    }

    @Test
    void refusesAPointWithAFieldTheSchemaDoesNotHave() {
        assertRefused("""
                {"points": [{"day": "d1", "time": "t1", "x": "3", "y": "0", "mode": "on", "z": "1"}]}
                """, "point 1", "z");
    }

    @Test
    void refusesABlankValue() {
        assertRefused("""
                {"points": [{"day": "d1", "time": "  ", "x": "3", "y": "0", "mode": "on"}]}
                """, "point 1", "time");
    }

    @Test
    void refusesAValueOutsideItsVocabulary() {
        assertRefused("""
                {"points": [{"day": "d1", "time": "t1", "x": "3", "y": "0", "mode": "maybe"}]}
                """, "point 1", "mode", "maybe");
    }

    /** Two points in one slot would overwrite each other in the batch, silently. */
    @Test
    void refusesTwoPointsInTheSameSlot() {
        assertRefused("""
                {"points": [
                    {"day": "d1", "time": "t1", "x": "3", "y": "0", "mode": "on"},
                    {"day": "d1", "time": "t1", "x": "9", "y": "1", "mode": "off"}]}
                """, "point 2", "d1 t1");
    }

    @Test
    void sendsANumberAsANumberAndEverythingElseAsText() {
        assertEquals(new BigDecimal("90"), PointSchema.sendable("90"));
        assertEquals(new BigDecimal("5.3"), PointSchema.sendable("5.3"));
        assertEquals(new BigDecimal("-1"), PointSchema.sendable("-1"));
        assertEquals("2000-01-01", PointSchema.sendable("2000-01-01"));
        assertEquals("12:00:00", PointSchema.sendable("12:00:00"));
        assertEquals("on", PointSchema.sendable("on"));
    }

    private static void assertRefused(String answer, String... named) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> SCHEMA.validate(json(answer)));
        for (String part : named) {
            assertTrue(thrown.getMessage().contains(part),
                    () -> "expected \"" + part + "\" in: " + thrown.getMessage());
        }
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
