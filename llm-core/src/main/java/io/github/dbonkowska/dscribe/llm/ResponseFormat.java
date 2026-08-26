package io.github.dbonkowska.dscribe.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResponseFormat(String type, @JsonProperty("json_schema") Schema jsonSchema) {

    public record Schema(String name, boolean strict, Object schema) {}

    public static ResponseFormat jsonSchema(String name, Object schema) {
        return new ResponseFormat("json_schema", new Schema(name, true, schema));
    }
}