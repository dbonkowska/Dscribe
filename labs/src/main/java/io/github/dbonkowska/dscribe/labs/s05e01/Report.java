package io.github.dbonkowska.dscribe.labs.s05e01;

import io.github.dbonkowska.dscribe.labs.s05e01.TaskParams.Field;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the extraction has found so far, one value per configured field, empty meaning "not found".
 *
 * <p>Two decisions live here rather than in the prompt. Whether to pay for the next tier is
 * {@link #missing()}, read by code. How each value is written for the hub is {@link #answer()}: the
 * model reports what the material says, and code rounds, parses and strips — a model asked to round
 * may truncate, and nothing downstream would say the difference was only the spelling.
 *
 * <p>The field names are the exercise's words and come from the bundle, so nothing here names one.
 *
 * @param fields the configured fields, in the order they are sent
 * @param values the value found for each field name, empty where nothing was found
 */
record Report(List<Field> fields, Map<String, String> values) {

    Report {
        fields = List.copyOf(fields);
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * From the model's reply. A key it left out — a provider can ignore the schema — is a field not
     * found, not a failure; a key it invented is ignored.
     */
    static Report from(List<Field> fields, Map<String, ?> reply) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : fields) {
            Object value = reply.get(field.name());
            values.put(field.name(), value == null ? "" : value.toString());
        }
        return new Report(fields, values);
    }

    /** The fields still empty or blank — the only thing that decides whether the next tier runs. */
    List<String> missing() {
        return fields.stream()
                .map(Field::name)
                .filter(name -> values.get(name).isBlank())
                .toList();
    }

    /**
     * A later tier saw everything an earlier one did and more, so where it found a value it wins.
     * Where it found nothing, the earlier value stands: a field one tier filled must not be emptied
     * by the next one failing to find it again.
     */
    Report mergedWith(Report later) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Field field : fields) {
            String found = later.values().getOrDefault(field.name(), "");
            merged.put(field.name(), found.isBlank() ? values.get(field.name()) : found);
        }
        return new Report(fields, merged);
    }

    /** The values as the hub wants them, in configured order. Refused while any field is missing. */
    Map<String, Object> answer() {
        List<String> missing = missing();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Nothing was sent: no value was found for " + missing + ".");
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        for (Field field : fields) {
            answer.put(field.name(), written(field, values.get(field.name()).strip()));
        }
        return answer;
    }

    private static Object written(Field field, String found) {
        try {
            return switch (field.format()) {
                // true rounding, never truncation, and always two places: the hub compares the string
                case "decimal2" -> new BigDecimal(found.replace(',', '.'))
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString();
                case "integer" -> Integer.parseInt(found);
                case "digits" -> {
                    String digits = found.replaceAll("[^0-9]", "");
                    if (digits.isEmpty()) {
                        throw new NumberFormatException("no digits");
                    }
                    yield digits;
                }
                default -> found;
            };
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Nothing was sent: " + field.name() + " is '" + found + "', which cannot be written as "
                            + field.format() + ".", e);
        }
    }

    /** One required string per field and nothing else; the model reports, code formats. */
    static ObjectNode schema(List<Field> fields) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Field field : fields) {
            properties.putObject(field.name()).put("type", "string");
            required.add(field.name());
        }
        schema.put("additionalProperties", false);
        return schema;
    }
}
