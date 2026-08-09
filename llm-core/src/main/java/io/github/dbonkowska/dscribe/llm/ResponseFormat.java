package io.github.dbonkowska.dscribe.llm;

public record ResponseFormat(String type, Schema json_schema) {

    public record Schema(String name, boolean strict, Object schema) {}

    public static ResponseFormat jsonSchema(String name, Object schema) {
        return new ResponseFormat("json_schema", new Schema(name, true, schema));
    }
}