package io.github.dbonkowska.dscribe.labs.s02e03;

import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The way back from the map to the source: the raw lines logged around a moment.
 *
 * <p>The map says an event happened, how often, and between when. It cannot say what else was
 * going on at the time, and that is usually what decides whether an event is a cause or background.
 * This answers it for one window at a time, so the judgement is made over a few dozen lines rather
 * than the whole source.
 *
 * <p>Addressed by time rather than by entry, because the question is about a moment — "what was
 * happening just before this stopped?" — and a moment can hold lines from many entries.
 */
final class ZoomTool {

    /**
     * The whole schema the model sees.
     *
     * @param at      a moment, in the form the map shows: {@code YYYY-MM-DD HH:MM}
     * @param minutes how far either side of it to look
     */
    record Window(String at, int minutes) {}

    private final EventMap map;

    /**
     * How wide a window may be, either side. Enforced rather than suggested: one call asking for the
     * whole day would put the whole source back into the conversation, which is the one thing the
     * map exists to prevent.
     */
    private final int maxMinutes;

    ZoomTool(EventMap map, int maxMinutes) {
        this.map = map;
        this.maxMinutes = maxMinutes;
    }

    /**
     * Name and description come from the lesson bundle: they are prompt surface. The bounds go into
     * the schema as well as being checked below — the schema so a compliant provider cannot ask for
     * more, the check because a provider is not obliged to be compliant.
     */
    Tool<Window> tool(String name, String description) {
        ObjectNode schema = SchemaUtils.from(Window.class);
        ObjectNode minutes = SchemaUtils.at(schema, "/properties/minutes");
        minutes.put("minimum", 0);
        minutes.put("maximum", maxMinutes);

        return new Tool<>(name, description, Window.class, args -> ToolOutput.of(look(args)), schema);
    }

    /** Each refusal throws: all of them are a malformed request to correct and make again. */
    private String look(Window window) {
        LocalDateTime at = parse(window.at());

        if (window.minutes() < 0 || window.minutes() > maxMinutes) {
            throw new IllegalArgumentException(
                    "minutes must be between 0 and " + maxMinutes + ", not " + window.minutes()
                            + ". Look at a narrower window, or at several.");
        }

        List<String> lines = map.around(at, window.minutes());
        if (lines.isEmpty()) {
            return "Nothing was logged within " + window.minutes() + " minute(s) of " + window.at() + ".";
        }
        return String.join("\n", lines);
    }

    private static LocalDateTime parse(String at) {
        if (at != null) {
            try {
                return LocalDateTime.parse(at, EventMap.MINUTE);
            } catch (DateTimeParseException e) {
                // falls through to the refusal, which says what form was expected
            }
        }
        throw new IllegalArgumentException(
                "'" + at + "' is not a moment in the form YYYY-MM-DD HH:MM."
                        + " Copy a time exactly as the map shows it.");
    }
}
