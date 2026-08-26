package io.github.dbonkowska.dscribe.agent;

import io.github.dbonkowska.dscribe.llm.FunctionSpec;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The answer tool's schema is the only thing standing between a model and a plausible-looking
 * answer in the wrong shape. A generated schema types a field as "some string"; where the real
 * vocabulary is a closed set, the caller narrows it — and a narrowing that is quietly dropped
 * looks identical to one that was never applied, right up until the submission is rejected.
 *
 * <p>The fixture vocabulary is invented and belongs to no lesson.
 */
class AnswerToolTest {

    record Verdict(String plant, int level) {}

    @Test
    void derivesItsSchemaFromTheAnswerTypeByDefault() {
        FunctionSpec function = new AnswerTool<>("answer", "reports it", Verdict.class).spec().function();

        assertEquals("answer", function.name());

        Set<String> required = new HashSet<>();
        function.parameters().at("/required").forEach(node -> required.add(node.stringValue()));

        assertEquals(Set.of("plant", "level"), required);
    }

    @Test
    void offersTheModelASchemaTheCallerNarrowed() {
        ObjectNode narrowed = SchemaUtils.from(Verdict.class);
        ArrayNode allowed = SchemaUtils.at(narrowed, "/properties/plant").putArray("enum");
        List.of("AAA0000XX", "BBB1111YY").forEach(allowed::add);

        ObjectNode offered = new AnswerTool<>("answer", "reports it", Verdict.class, narrowed)
                .spec()
                .function()
                .parameters();

        List<String> vocabulary = new ArrayList<>();
        offered.at("/properties/plant/enum").forEach(node -> vocabulary.add(node.stringValue()));

        assertEquals(List.of("AAA0000XX", "BBB1111YY"), vocabulary);
    }
}