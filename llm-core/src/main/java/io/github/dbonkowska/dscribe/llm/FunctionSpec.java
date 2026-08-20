package io.github.dbonkowska.dscribe.llm;

import tools.jackson.databind.node.ObjectNode;

/**
 * What the model is told about one callable function.
 *
 * <p>Top-level rather than nested in {@link ToolSpec}, where it would naturally be called
 * {@code Function} and collide with {@code java.util.function.Function} at every call site
 * that builds a tool.
 *
 * @param strict binds the model to {@code parameters} instead of treating it as a hint
 */
public record FunctionSpec(String name, String description, ObjectNode parameters, boolean strict) {}