package io.github.dbonkowska.dscribe.labs.s05e04;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only thing the model decides in a run, narrowed so an invented direction cannot be represented,
 * and checked again in code because a provider can ignore the schema.
 */
class BearingTest {

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    @Test
    void narrowsTheLabelToTheFourBearings() {
        JsonNode schema = Bearing.schema();

        assertEquals(List.of("left", "ahead", "right", "unclear"), strings(schema.at("/properties/bearing/enum")));
    }

    @Test
    void requiresTheLabelAndNothingElse() {
        JsonNode schema = Bearing.schema();

        assertEquals(List.of("bearing"), strings(schema.path("required")));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }

    @ParameterizedTest
    @EnumSource(Bearing.class)
    void readsEachLabel(Bearing bearing) {
        assertEquals(bearing, Bearing.parse(Map.of("bearing", bearing.label())));
    }

    @Test
    void refusesALabelOutsideTheSet() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Bearing.parse(Map.of("bearing", "up")));

        assertTrue(thrown.getMessage().contains("up"), thrown::getMessage);
    }

    @Test
    void refusesAReplyWithNoLabel() {
        assertThrows(IllegalStateException.class, () -> Bearing.parse(Map.of()));
    }
}
