package io.github.dbonkowska.dscribe.labs.s05e05;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A free-text hint taken apart into a base, a correction and which way the correction goes.
 *
 * <p>The model only takes the text apart. Code does the arithmetic, so a model that miscounts cannot
 * hand over a finished, wrong value. {@link Direction#UNCLEAR} lets it say it cannot tell, which then
 * counts as a failed reading rather than a guess.
 *
 * @param base      the value the hint starts from
 * @param delta     the correction applied to it
 * @param direction which way the correction goes
 */
record HintReading(int base, int delta, Direction direction) {

    private static final String BASE = "base";
    private static final String DELTA = "delta";
    private static final String DIRECTION = "direction";

    /** The run's own labels: they name arithmetic, not anything in the hint. */
    enum Direction {
        ADD, SUBTRACT, UNCLEAR;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a reading comes to: a value to set, or why there is none. */
    sealed interface Value {
        record Of(int value) implements Value {}

        record None(String reason) implements Value {}
    }

    static ObjectNode schema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject(BASE).put("type", "integer");
        properties.putObject(DELTA).put("type", "integer");
        ObjectNode direction = properties.putObject(DIRECTION);
        direction.put("type", "string");
        ArrayNode labels = direction.putArray("enum");
        labels().forEach(labels::add);

        schema.putArray("required").add(BASE).add(DELTA).add(DIRECTION);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** From the model's reply, checked again here since a provider can ignore a schema. */
    static HintReading parse(Map<?, ?> reply) {
        if (reply == null) {
            throw new IllegalStateException("The model gave no reply: its answer was the JSON literal null.");
        }
        String label = String.valueOf(reply.get(DIRECTION));
        Direction direction = Arrays.stream(Direction.values())
                .filter(candidate -> candidate.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The model answered direction " + label + ", outside " + labels() + "."));
        return new HintReading(whole(reply, BASE), whole(reply, DELTA), direction);
    }

    /** The base corrected by the delta, when the reading is clear and lands in {@code [min, max]}. */
    Value value(int min, int max) {
        if (direction == Direction.UNCLEAR) {
            return new Value.None("The model could not tell which way the correction goes.");
        }
        long value = direction == Direction.ADD ? (long) base + delta : (long) base - delta;
        if (value < min || value > max) {
            return new Value.None(
                    "The reading comes to " + value + " (" + base + " " + direction.label() + " " + delta
                            + "), outside the range the device takes, " + min + " to " + max + ".");
        }
        return new Value.Of((int) value);
    }

    /** Integral types only: a fraction or a word is not a part of a hint that names a whole number. */
    private static int whole(Map<?, ?> reply, String key) {
        Object value = reply.get(key);
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)) {
            throw new IllegalStateException(
                    "The model answered " + key + " " + value + ", which is not a whole number.");
        }
        long number = ((Number) value).longValue();
        if (number != (int) number) {
            throw new IllegalStateException("The model answered " + key + " " + value + ", too large to be a setting.");
        }
        return (int) number;
    }

    private static List<String> labels() {
        return Arrays.stream(Direction.values()).map(Direction::label).toList();
    }
}
