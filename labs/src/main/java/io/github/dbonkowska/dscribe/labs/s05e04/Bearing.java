package io.github.dbonkowska.dscribe.labs.s05e04;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which side of the current position a free-text hint points at: the only decision the model makes
 * in a run, and the reason a model is here at all.
 *
 * <p>The run's own labels, not the exercise's commands. {@link Steering} decides what to do about a
 * bearing, so a wrong label here cannot name a move. {@link #UNCLEAR} lets the model say it cannot
 * tell, which matters more than the others: without it a hint it cannot read becomes a guess.
 */
enum Bearing {
    LEFT, AHEAD, RIGHT, UNCLEAR;

    private static final String KEY = "bearing";

    String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ObjectNode schema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode bearing = schema.putObject("properties").putObject(KEY);
        bearing.put("type", "string");
        ArrayNode labels = bearing.putArray("enum");
        labels().forEach(labels::add);

        schema.putArray("required").add(KEY);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** From the model's reply. A label outside the set is refused, since a provider can ignore a schema. */
    static Bearing parse(Map<?, ?> reply) {
        String label = String.valueOf(reply.get(KEY));
        return Arrays.stream(values())
                .filter(bearing -> bearing.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The model answered bearing " + label + ", outside " + labels() + "."));
    }

    private static List<String> labels() {
        return Arrays.stream(values()).map(Bearing::label).toList();
    }
}
