package io.github.dbonkowska.dscribe.labs.s05e02;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What one reply was, as the model labels it — the only decision in a call the model makes.
 *
 * <p>Both fields are narrowed by the schema, built at run time from the bundle so nothing here names
 * a label or a candidate: {@code label} to the dialogue's own two plus every interrupt, {@code chosen}
 * to the candidates. A provider can ignore a schema, so {@link #parse} checks the same sets again
 * before {@link Dialogue} acts on either.
 *
 * @param label  {@link TaskParams#PROCEED}, {@link TaskParams#OTHER} or an interrupt's name
 * @param chosen the candidates this reply chose, possibly none
 */
record Classification(String label, List<String> chosen) {

    Classification {
        chosen = List.copyOf(chosen);
    }

    static ObjectNode schema(TaskParams task) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode label = properties.putObject("label");
        label.put("type", "string");
        ArrayNode labels = label.putArray("enum");
        labels(task).forEach(labels::add);

        ObjectNode chosen = properties.putObject("chosen");
        chosen.put("type", "array");
        ObjectNode items = chosen.putObject("items");
        items.put("type", "string");
        ArrayNode candidates = items.putArray("enum");
        task.candidates().forEach(candidates::add);

        schema.putArray("required").add("label").add("chosen");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * From the model's reply. A missing {@code chosen} is nothing chosen; a label or a choice outside
     * its set is refused, because acting on one would say a line nobody wrote a reason for.
     */
    static Classification parse(Map<?, ?> reply, TaskParams task) {
        String label = String.valueOf(reply.get("label"));
        List<String> allowed = labels(task);
        if (!allowed.contains(label)) {
            throw new IllegalStateException("The classifier answered label " + label + ", outside " + allowed + ".");
        }

        List<String> chosen = new ArrayList<>();
        if (reply.get("chosen") instanceof List<?> values) {
            for (Object value : values) {
                String candidate = String.valueOf(value);
                if (!task.candidates().contains(candidate)) {
                    throw new IllegalStateException(
                            "The classifier chose " + candidate + ", which is not among " + task.candidates() + ".");
                }
                chosen.add(candidate);
            }
        }
        return new Classification(label, chosen);
    }

    private static List<String> labels(TaskParams task) {
        List<String> labels = new ArrayList<>(List.of(TaskParams.PROCEED, TaskParams.OTHER));
        task.interrupts().forEach(interrupt -> labels.add(interrupt.name()));
        return labels;
    }
}
