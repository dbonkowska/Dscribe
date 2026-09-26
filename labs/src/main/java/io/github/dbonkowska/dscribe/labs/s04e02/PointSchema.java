package io.github.dbonkowska.dscribe.labs.s04e02;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What the model is asked to answer, and the check its answer passes before anything is signed.
 *
 * <p>No field is named in code. The hub's help reply lists what its signing action needs and what a
 * stored point needs; a point is the union of the two, less the signature, which the run adds after
 * signing. Every value is asked for as a string, and a field with a closed vocabulary gets it as an
 * {@code enum} — a value outside it is then one the model cannot write.
 *
 * <p>The schema narrows the model; {@link #validate} is still run, because a provider can ignore a
 * schema, and inside the window a malformed point found before signing costs nothing, where one
 * found after costs the attempt.
 */
public final class PointSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A plain decimal: what the hub treats as a number. Anything else — a date, a time — is text. */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    /** The schema's own name for the list of points — not the hub's vocabulary. */
    private static final String POINTS = "points";

    private final List<String> fields;
    private final List<String> signFields;
    private final List<String> entryFields;
    private final List<String> slotFields;
    private final Map<String, List<String>> enums;

    private PointSchema(
            List<String> fields, List<String> signFields, List<String> entryFields, List<String> slotFields,
            Map<String, List<String>> enums) {
        this.fields = fields;
        this.signFields = signFields;
        this.entryFields = entryFields;
        this.slotFields = slotFields;
        this.enums = enums;
    }

    /**
     * @param help           the help reply
     * @param signFields     a JSON pointer to the signing action's field list in it
     * @param configFields   a JSON pointer to a single stored point's field list in it
     * @param signatureField the field the signature travels in, dropped from what the model answers
     * @param slotFields     the fields a batch entry is filed under
     * @param enums          closed vocabularies, by field
     */
    public static PointSchema from(
            JsonNode help, String signFields, String configFields, String signatureField,
            List<String> slotFields, Map<String, List<String>> enums) {

        List<String> signing = without(list(help, signFields), signatureField);
        List<String> stored = without(list(help, configFields), signatureField);

        Set<String> union = new LinkedHashSet<>(signing);
        union.addAll(stored);
        List<String> fields = List.copyOf(union);

        for (String slot : slotFields) {
            if (!fields.contains(slot)) {
                throw new IllegalStateException(
                        "slotFields names " + slot + ", which is not a field of a point " + fields
                                + ". Fix it in the lesson's task.properties.");
            }
        }
        for (String field : enums.keySet()) {
            if (!fields.contains(field)) {
                throw new IllegalStateException(
                        "enums." + field + " is for a field a point does not have " + fields
                                + ". Fix it in the lesson's task.properties.");
            }
        }

        List<String> entry = stored.stream().filter(f -> !slotFields.contains(f)).toList();
        return new PointSchema(fields, signing, entry, List.copyOf(slotFields), Map.copyOf(enums));
    }

    /** Every field of a point, in the order the help reply first names them. */
    public List<String> fields() {
        return fields;
    }

    /** The fields a signing request carries. */
    public List<String> signFields() {
        return signFields;
    }

    /** The fields of a batch entry: a stored point's fields less the ones it is filed under. */
    public List<String> entryFields() {
        return entryFields;
    }

    /** The key a point is filed under in a batch: its slot fields, joined with a space. */
    public String slot(Map<String, String> point) {
        return slotFields.stream().map(point::get).collect(Collectors.joining(" "));
    }

    /** A strict JSON schema: an object holding a list of points, every field a required string. */
    public JsonNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        for (String field : fields) {
            ObjectNode property = properties.putObject(field);
            property.put("type", "string");
            List<String> vocabulary = enums.get(field);
            if (vocabulary != null) {
                ArrayNode allowed = property.putArray("enum");
                vocabulary.forEach(allowed::add);
            }
        }
        ObjectNode point = MAPPER.createObjectNode();
        point.put("type", "object");
        point.set("properties", properties);
        ArrayNode required = point.putArray("required");
        fields.forEach(required::add);
        point.put("additionalProperties", false);

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode points = schema.putObject("properties").putObject(POINTS);
        points.put("type", "array");
        points.set("items", point);
        schema.putArray("required").add(POINTS);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * The model's answer as points, each a field-to-value map in field order — or a refusal naming
     * the point (counted from 1) and the field that is wrong.
     */
    public List<Map<String, String>> validate(JsonNode answer) {
        JsonNode items = answer.path(POINTS);
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("The model's answer has no points: " + answer);
        }
        List<Map<String, String>> points = new ArrayList<>();
        Map<String, Integer> slots = new HashMap<>();
        int number = 0;
        for (JsonNode item : items) {
            number++;
            String where = "point " + number + " " + item;
            for (String name : item.propertyNames()) {
                if (!fields.contains(name)) {
                    throw new IllegalStateException(
                            where + " has a field " + name + " the schema does not have " + fields + ".");
                }
            }
            Map<String, String> point = new LinkedHashMap<>();
            for (String field : fields) {
                JsonNode value = item.get(field);
                if (value == null || !value.isString() || value.asString().isBlank()) {
                    throw new IllegalStateException(where + " has no value for " + field + ".");
                }
                List<String> vocabulary = enums.get(field);
                if (vocabulary != null && !vocabulary.contains(value.asString())) {
                    throw new IllegalStateException(
                            where + " sets " + field + " to " + value.asString() + ", not one of " + vocabulary + ".");
                }
                point.put(field, value.asString().strip());
            }
            Integer earlier = slots.putIfAbsent(slot(point), number);
            if (earlier != null) {
                throw new IllegalStateException(
                        where + " is in slot " + slot(point) + ", which point " + earlier + " already has.");
            }
            points.add(point);
        }
        return points;
    }

    /**
     * How a value the model wrote as a string is sent to the hub: a plain decimal as a number, and
     * anything else as the text it is. A hypothesis about the hub, which the completion action tests.
     */
    public static Object sendable(String value) {
        return NUMBER.matcher(value).matches() ? new BigDecimal(value) : value;
    }

    private static List<String> list(JsonNode help, String pointer) {
        JsonNode node = help.at(pointer);
        List<String> names = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> {
                if (n.isString() && !n.asString().isBlank()) {
                    names.add(n.asString());
                }
            });
        }
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "The help reply has no field list at " + pointer + ". Check the pointer in the lesson's"
                            + " task.properties against the reply in the transcript.");
        }
        return names;
    }

    private static List<String> without(List<String> names, String dropped) {
        return names.stream().filter(n -> !n.equals(dropped)).toList();
    }
}
