package io.github.dbonkowska.dscribe.labs.s02e03;

import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one way back from the map to the source, and the one way to pull the source back into the
 * conversation. Both directions fail quietly: a window that is off by an edge hides the line just
 * before a failure, and a window with no ceiling lets a single call undo the whole compression —
 * nothing errors in either case, the run just reasons worse or costs more.
 *
 * <p>Lines, severities and times are invented and belong to no lesson.
 */
class ZoomToolTest {

    private static final EventMap MAP = EventMap.parse(String.join("\n",
            "2030-01-01 10:00 HIGH pump stalled",
            "2030-01-01 10:05 LOW fan ok",
            "2030-01-01 10:10 HIGH pump stalled",
            "2030-01-01 10:20 MID valve slow"),
            Pattern.compile("(?<date>\\S+) (?<time>\\S+) (?<severity>\\w+) (?<message>.*)"));

    private static final int MAX = 30;

    private static String zoom(String at, int minutes) {
        return (String) tool().handler().apply(new ZoomTool.Window(at, minutes)).result();
    }

    private static Tool<ZoomTool.Window> tool() {
        return new ZoomTool(MAP, MAX).tool("look", "looks");
    }

    /**
     * Both edges are in. The lines exactly {@code minutes} away are the ones most likely to be the
     * cause of whatever sits at {@code at}, and an exclusive edge drops them without a sign.
     *
     * <p>Each carries its entry's id, because a line found here is only useful if the model can
     * put it in a submission — and a submission is made of ids, not of lines.
     */
    @Test
    void returnsTheRawLinesInsideTheWindowInclusiveTaggedWithTheirEntry() {
        assertEquals(List.of(
                        "E1 2030-01-01 10:00 HIGH pump stalled",
                        "E2 2030-01-01 10:05 LOW fan ok",
                        "E1 2030-01-01 10:10 HIGH pump stalled"),
                zoom("2030-01-01 10:05", 5).lines().toList());
    }

    /** An empty string reads as a tool that failed to answer, not as an answer of "nothing here". */
    @Test
    void saysSoWhenNothingWasLoggedInTheWindow() {
        String result = zoom("2030-01-01 11:00", 0);

        assertFalse(result.isBlank());
        assertTrue(result.contains("2030-01-01 11:00"), () -> "it has to say which window was empty: " + result);
    }

    /** The map shows full timestamps so the model can copy one; a bare time is a sign it did not. */
    @Test
    void refusesATimeNotInTheFormTheMapShows() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> zoom("10:05", 5));

        assertTrue(thrown.getMessage().contains("YYYY-MM-DD HH:MM"), thrown::getMessage);
    }

    /**
     * The ceiling is enforced here and not only offered in the schema, because a provider is not
     * obliged to honour a schema — and one call asking for the whole day puts the whole source back
     * in front of the model.
     */
    @Test
    void refusesAWindowWiderThanTheCeiling() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> zoom("2030-01-01 10:05", MAX + 1));

        assertTrue(thrown.getMessage().contains(String.valueOf(MAX)), thrown::getMessage);
    }

    @Test
    void refusesANegativeWindow() {
        assertThrows(IllegalArgumentException.class, () -> zoom("2030-01-01 10:05", -1));
    }

    @Test
    void offersTheCeilingInTheSchema() {
        JsonNode minutes = tool().spec().function().parameters().at("/properties/minutes");

        assertEquals(0, minutes.get("minimum").intValue());
        assertEquals(MAX, minutes.get("maximum").intValue());
    }
}
