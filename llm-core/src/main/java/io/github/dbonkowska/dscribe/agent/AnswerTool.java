package io.github.dbonkowska.dscribe.agent;

import io.github.dbonkowska.dscribe.llm.ToolSpec;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tool a run finishes by calling. It has no handler because calling it *is* the result: the
 * arguments the model sends are deserialised into {@code type} and returned from
 * {@link Agent#run}.
 *
 * <p>Name and description come from the caller — only the Java type is structural.
 *
 * @param parameters the schema the model is bound to. Defaults to one generated from
 *                   {@code type}; pass your own to narrow what the generator could only infer
 *                   loosely — a field the type says is a {@code String} but the domain says is
 *                   one of a fixed set. Strict mode then makes a wrong value unrepresentable
 *                   rather than merely unlikely.
 */
public record AnswerTool<T>(String name, String description, Class<T> type, ObjectNode parameters) {

    public AnswerTool(String name, String description, Class<T> type) {
        this(name, description, type, SchemaUtils.from(type));
    }

    public ToolSpec spec() {
        return ToolSpec.function(name, description, parameters);
    }
}
