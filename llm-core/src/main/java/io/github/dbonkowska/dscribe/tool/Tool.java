package io.github.dbonkowska.dscribe.tool;

import io.github.dbonkowska.dscribe.llm.ToolSpec;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import tools.jackson.databind.node.ObjectNode;

import java.util.function.Function;

/**
 * A function the model may call, paired with the record its arguments arrive as. The record is
 * the single source of both halves — the schema the model is given and the type the arguments
 * are deserialised into — so the two cannot drift apart.
 *
 * @param handler    what the model reads back, and any image it should be shown — see
 *                   {@link ToolOutput}. The payload is serialised to JSON unless it is already a
 *                   {@code String}
 * @param parameters the schema the model is bound to. Defaults to one generated from
 *                   {@code argumentType}; pass your own to narrow what the generator could only
 *                   infer loosely — a field the type says is a {@code String} but the domain says
 *                   is one of a fixed set. Strict mode then makes a wrong value unrepresentable
 *                   rather than merely unlikely, which is worth more here than on the answer
 *                   tool: an intermediate call is one the run pays for and then has to recover
 *                   from.
 */
public record Tool<A>(
        String name,
        String description,
        Class<A> argumentType,
        Function<A, ToolOutput> handler,
        ObjectNode parameters) {

    public Tool(String name, String description, Class<A> argumentType, Function<A, ToolOutput> handler) {
        this(name, description, argumentType, handler, SchemaUtils.from(argumentType));
    }

    public ToolSpec spec() {
        return ToolSpec.function(name, description, parameters);
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