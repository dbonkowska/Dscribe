package io.github.dbonkowska.dscribe.tool;

import io.github.dbonkowska.dscribe.llm.ToolSpec;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;

import java.util.function.Function;

/**
 * A function the model may call, paired with the record its arguments arrive as. The record is
 * the single source of both halves — the schema the model is given and the type the arguments
 * are deserialised into — so the two cannot drift apart.
 *
 * @param handler what the model reads back, and any image it should be shown — see
 *                {@link ToolOutput}. The payload is serialised to JSON unless it is already a
 *                {@code String}
 */
public record Tool<A>(
        String name,
        String description,
        Class<A> argumentType,
        Function<A, ToolOutput> handler) {

    public ToolSpec spec() {
        return ToolSpec.function(name, description, SchemaUtils.from(argumentType));
    }

    /**
     * Runs the handler on arguments already deserialised into {@link #argumentType()}.
     *
     * <p>A {@code Tool<?>} pulled out of a heterogeneous collection cannot have
     * {@code handler().apply(...)} called on it — the wildcard makes the parameter type
     * uninhabitable. The cast that gets around it lives here, once, rather than being
     * suppressed at every dispatch site.
     */
    @SuppressWarnings("unchecked")
    ToolOutput apply(Object arguments) {
        return handler.apply((A) arguments);
    }
}