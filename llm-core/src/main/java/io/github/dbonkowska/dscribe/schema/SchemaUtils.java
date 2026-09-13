package io.github.dbonkowska.dscribe.schema;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import tools.jackson.databind.node.ObjectNode;

public class SchemaUtils {

    private static final SchemaGenerator GENERATOR = createGenerator();

    private static SchemaGenerator createGenerator() {
        SchemaGeneratorConfigBuilder config = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2019_09, OptionPreset.PLAIN_JSON)
                .with(new JacksonSchemaModule())
                .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                // record components are private fields with no get* accessor
                .with(Option.NONPUBLIC_NONSTATIC_FIELDS_WITHOUT_GETTERS)
                // render enum constants as their toString() label rather than name()
                .with(Option.FLATTENED_ENUMS_FROM_TOSTRING)
                .without(Option.FLATTENED_ENUMS);

        // strict structured output rejects a schema unless every property is required
        config.forFields().withRequiredCheck(field -> true);

        return new SchemaGenerator(config.build());
    }

    /**
     * The schema for a type, always shaped as a closed object.
     *
     * <p>The generator omits {@code properties} and {@code required} for a type with no
     * components — a tool the model calls to trigger something rather than to pass anything.
     * That leaves a strict-mode request asserting a closed object without saying what it is
     * closed around, which is at best provider-dependent, and the place it would be found out is
     * the first tool call of a run that has already been paid for. Both keys are filled in here
     * so the shape is the same one every other schema has.
     */
    public static ObjectNode from(Class<?> clazz) {
        ObjectNode schema = GENERATOR.generateSchema(clazz);

        if (!schema.has("properties")) {
            schema.putObject("properties");
        }
        if (!schema.has("required")) {
            schema.putArray("required");
        }
        return schema;
    }

    /**
     * The object a JSON Pointer addresses inside a generated schema, for narrowing a
     * property the generator could only infer loosely — an enum of allowed strings, say,
     * where the Java type is just {@code String}.
     *
     * @throws IllegalArgumentException if the pointer misses, which in practice means the
     *                                  schema shape changed and the caller's pointer is stale
     */
    public static ObjectNode at(ObjectNode schema, String pointer) {
        if (schema.at(pointer) instanceof ObjectNode node) {
            return node;
        }
        throw new IllegalArgumentException("No object at " + pointer + " in schema: " + schema);
    }
}