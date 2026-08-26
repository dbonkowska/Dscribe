package io.github.dbonkowska.dscribe.tool;

import io.github.dbonkowska.dscribe.llm.ToolSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            new Tool<>("lookup", "finds things", Lookup.class, Lookup::query);

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
}