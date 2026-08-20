package io.github.dbonkowska.dscribe.llm;

import tools.jackson.databind.node.ObjectNode;

/**
 * One entry of the request's {@code tools} array. {@code type} is a discriminator with exactly
 * one value in play today, which is why the factory fills it in.
 */
public record ToolSpec(String type, FunctionSpec function) {

    public static ToolSpec function(String name, String description, ObjectNode parameters) {
        return new ToolSpec("function", new FunctionSpec(name, description, parameters, true));
    }
}