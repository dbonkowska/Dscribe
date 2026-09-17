package io.github.dbonkowska.dscribe.labs.s02e04;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends an answer, after making sure it is one.
 *
 * <p>Every call carries every field. There is no stored draft to fill in piece by piece: a draft
 * kept here and the answer the model believes it gave are two copies that drift, and the model then
 * reasons about the one that was never sent. Restating a handful of short values is the cheaper
 * trade, and it is the one s02e03 earned its flag with.
 *
 * <p>The field names are the exercise's words and come from the bundle, so the schema cannot be a
 * record with one component per field. It is a list of name–value pairs instead, with the name
 * narrowed to the configured set — and completeness, which a list cannot express, checked here.
 */
final class SubmitTool {

    private static final Logger log = LoggerFactory.getLogger(SubmitTool.class);

    /** One field of the answer. */
    record Value(String field, String value) {}

    /** The whole schema the model sees: a value for every field, each once. */
    record Submission(List<Value> values) {}

    /**
     * The facts about the exercise this needs, all supplied from outside the repository.
     *
     * @param fields what an answer is made of, in the order it is sent
     */
    record Spec(List<TaskParams.Field> fields) {}

    private final ResilientHub hub;
    private final Spec spec;

    /** Every body the hub returned, so a reported result can be checked against what was said. */
    private final List<String> responses = new ArrayList<>();

    private int sent;

    SubmitTool(ResilientHub hub, Spec spec) {
        this.hub = hub;
        this.spec = spec;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Submission> tool(String name, String description) {
        ObjectNode schema = SchemaUtils.from(Submission.class);
        ArrayNode allowed = SchemaUtils.at(schema, "/properties/values/items/properties/field").putArray("enum");
        for (TaskParams.Field field : spec.fields()) {
            allowed.add(field.name());
        }

        return new Tool<>(name, description, Submission.class, args -> ToolOutput.of(submit(args.values())), schema);
    }

    List<String> responses() {
        return List.copyOf(responses);
    }

    /**
     * Each refusal throws. {@code Toolbox} turns that into a tool result the model reads, and every
     * one of them is something to correct and send again.
     */
    private String submit(List<Value> values) {
        Map<String, String> byField = new LinkedHashMap<>();
        for (Value value : values) {
            if (spec.fields().stream().noneMatch(field -> field.name().equals(value.field()))) {
                throw new IllegalArgumentException(
                        "No field " + value.field() + ". Nothing was sent. The answer's fields are "
                                + names() + ".");
            }
            if (byField.put(value.field(), value.value()) != null) {
                throw new IllegalArgumentException(
                        value.field() + " was given more than once. Nothing was sent. Give each field"
                                + " exactly once.");
            }
        }

        // in the configured order rather than the model's, so every submission reads the same way
        Map<String, String> answer = new LinkedHashMap<>();
        for (TaskParams.Field field : spec.fields()) {
            if (!byField.containsKey(field.name())) {
                throw new IllegalArgumentException(
                        field.name() + " is missing. Nothing was sent. Every submission carries every"
                                + " field: " + names() + ".");
            }
            answer.put(field.name(), byField.get(field.name()));
        }

        // counted before sending, not from the responses kept: a submission whose retries run out
        // throws after reaching the hub, and the next one must not reuse its label in the record
        String label = "submission " + (++sent);
        log.info("{}: {}", label, answer);

        String response = hub.call(label, answer);
        responses.add(response);
        return response;
    }

    private List<String> names() {
        return spec.fields().stream().map(TaskParams.Field::name).toList();
    }
}
