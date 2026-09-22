package io.github.dbonkowska.dscribe.labs.s03e04;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** City names here are invented; see {@link CatalogTest}'s note on the same rule. */
class ToolReplyTest {

    @Test
    void returnsTheNoMatchTextWhenNothingResolved() {
        String reply = ToolReply.of(List.of(), 4, 500);

        assertEquals(ToolReply.NO_MATCH, reply);
        assertDoesNotThrow(() -> ReplyBounds.require(reply, 4, 500));
    }

    @Test
    void joinsNamesThatFitComfortably() {
        String reply = ToolReply.of(List.of("City One", "City Two"), 4, 500);

        assertTrue(reply.contains("City One"), reply);
        assertTrue(reply.contains("City Two"), reply);
        assertDoesNotThrow(() -> ReplyBounds.require(reply, 4, 500));
    }

    /**
     * A cap too small for the full list. The result must keep only whole names — a truncated
     * "CityAl" would read as a different, real-looking name rather than an obviously cut one.
     */
    @Test
    void keepsOnlyWholeNamesUnderTheMaximumAndAppendsTheOverflowHint() {
        List<String> names = List.of(
                "CityAlpha", "CityBravo", "CityCharlie", "CityDelta", "CityEcho",
                "CityFoxtrot", "CityGolf", "CityHotel", "CityIndia", "CityJuliet");

        String reply = ToolReply.of(names, 4, 60);

        assertDoesNotThrow(() -> ReplyBounds.require(reply, 4, 60));
        assertTrue(reply.endsWith(ToolReply.OVERFLOW_HINT), reply);

        String withoutHint = reply.substring(0, reply.length() - ToolReply.OVERFLOW_HINT.length());
        List<String> included = withoutHint.isBlank() ? List.of() : List.of(withoutHint.split(", "));

        assertTrue(included.size() < names.size(), "the whole list must not have fit under the cap");
        assertTrue(names.containsAll(included), () -> "every kept entry must be a whole name: " + included);
        assertEquals(names.subList(0, included.size()), included, "kept entries stay in the original order");
    }
}
