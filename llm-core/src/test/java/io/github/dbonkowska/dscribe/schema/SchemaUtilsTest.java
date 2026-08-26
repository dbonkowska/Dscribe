package io.github.dbonkowska.dscribe.schema;

import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the structured-output contract. The options in {@link SchemaUtils} are easy to
 * "tidy up" into something that still compiles but quietly stops constraining the model.
 *
 * <p>The fixtures below are deliberately unrelated to any lesson — the behaviour under test
 * belongs to {@code SchemaUtils}, not to whichever exercise happens to use it. One label
 * carries a diacritic so the UTF-8 round trip keeps its teeth.
 */
class SchemaUtilsTest {

    private static final String LABEL_WITH_DIACRITIC = "źrebię";

    enum Animal {
        HORSE("horse"),
        FOAL(LABEL_WITH_DIACRITIC);

        private final String label;

        Animal(String label) {
            this.label = label;
        }

        @JsonValue
        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    record Sighting(int id, Animal animal) {}

    record Loose(int id, List<String> tags) {}

    @Test
    void rendersEnumConstantsAsTheirLabelRatherThanTheirName() {
        ObjectNode schema = SchemaUtils.from(Sighting.class);

        List<String> labels = new ArrayList<>();
        schema.at("/properties/animal/enum").forEach(node -> labels.add(node.stringValue()));

        assertEquals(List.of("horse", LABEL_WITH_DIACRITIC), labels);
    }

    @Test
    void marksEveryPropertyRequired() {
        // OpenAI-style strict mode rejects a schema whose properties are not all required
        ObjectNode schema = SchemaUtils.from(Sighting.class);

        // the generator orders this list alphabetically; only membership is the contract
        Set<String> required = new HashSet<>();
        schema.at("/required").forEach(node -> required.add(node.stringValue()));

        assertEquals(Set.of("id", "animal"), required);
    }

    @Test
    void forbidsExtraProperties() {
        ObjectNode schema = SchemaUtils.from(Sighting.class);

        assertFalse(schema.at("/additionalProperties").booleanValue());
    }

    @Test
    void enumConstantsSerialiseAndDeserialiseThroughTheirLabel() {
        ObjectMapper mapper = JsonMapper.builder().build();
        Sighting original = new Sighting(0, Animal.FOAL);

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains(LABEL_WITH_DIACRITIC), () -> "expected the label, got: " + json);

        assertEquals(Animal.FOAL, mapper.readValue(json, Sighting.class).animal());
    }

    @Test
    void atNarrowsALooselyTypedPropertyToAFixedVocabulary() {
        ObjectNode schema = SchemaUtils.from(Loose.class);

        ArrayNode allowed = SchemaUtils.at(schema, "/properties/tags/items").putArray("enum");
        List.of("one", "two").forEach(allowed::add);

        List<String> values = new ArrayList<>();
        schema.at("/properties/tags/items/enum").forEach(node -> values.add(node.stringValue()));

        assertEquals(List.of("one", "two"), values);
    }

    @Test
    void atRejectsAPointerThatMissesRatherThanFailingLater() {
        ObjectNode schema = SchemaUtils.from(Loose.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaUtils.at(schema, "/properties/nope/items"));
    }
}