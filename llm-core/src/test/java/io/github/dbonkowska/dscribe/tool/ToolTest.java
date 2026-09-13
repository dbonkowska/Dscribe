package io.github.dbonkowska.dscribe.tool;

import io.github.dbonkowska.dscribe.llm.ToolSpec;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec is the whole of what the model knows about a tool. If it loosens — strict mode off,
 * a property that stops being required, extra properties allowed — the model starts sending
 * arguments the handler cannot deserialise, and the failure surfaces one layer away as a
 * dispatch error rather than here.
 *
 * <p>The fixture tool is invented; {@code lookup} belongs to no lesson.
 */
class ToolTest {

    record Lookup(String query, int limit) {}

    private static final Tool<Lookup> LOOKUP =
            new Tool<>("lookup", "finds things", Lookup.class, args -> ToolOutput.of(args.query()));

    @Test
    void describesItselfAsAStrictFunctionTheProviderCanCall() {
        ToolSpec spec = LOOKUP.spec();

        assertEquals("function", spec.type());
        assertEquals("lookup", spec.function().name());
        assertEquals("finds things", spec.function().description());
        assertTrue(spec.function().strict(), "strict mode is what makes the arguments trustworthy");
    }

    @Test
    void generatesTheArgumentSchemaFromTheArgumentRecord() {
        ObjectNode parameters = LOOKUP.spec().function().parameters();

        assertFalse(
                parameters.at("/additionalProperties").booleanValue(),
                () -> "expected extra properties forbidden in: " + parameters);

        // the generator orders this list alphabetically; only membership is the contract
        Set<String> required = new HashSet<>();
        parameters.at("/required").forEach(node -> required.add(node.stringValue()));

        assertEquals(Set.of("query", "limit"), required);
    }

    /**
     * The reason this class changed. Until now only the answer tool could be narrowed, so every
     * intermediate call took whatever the model sent: a field the domain says is one of a fixed
     * set arrived as an unconstrained string, and a wrong value was caught — if at all — by the
     * handler, one spent call later.
     */
    @Test
    void carriesASuppliedSchemaRatherThanGeneratingOne() {
        ObjectNode narrowed = SchemaUtils.from(Lookup.class);
        ArrayNode allowed = SchemaUtils.at(narrowed, "/properties/query").putArray("enum");
        allowed.add("a");
        allowed.add("b");

        Tool<Lookup> pinned = new Tool<>(
                "lookup", "finds things", Lookup.class, args -> ToolOutput.of(args.query()), narrowed);

        ObjectNode parameters = pinned.spec().function().parameters();

        assertSame(narrowed, parameters, "the caller's node is what the model must be bound to");

        List<String> vocabulary = new ArrayList<>();
        parameters.at("/properties/query/enum").forEach(node -> vocabulary.add(node.stringValue()));
        assertEquals(List.of("a", "b"), vocabulary);
    }

    /** The narrowing is opt-in: a tool that asks for nothing keeps the generated schema. */
    @Test
    void generatesTheSchemaWhenTheCallerSuppliesNone() {
        assertEquals(SchemaUtils.from(Lookup.class), LOOKUP.spec().function().parameters());
    }
}
