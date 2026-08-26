package io.github.dbonkowska.dscribe.tool;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.ToolCall;
import io.github.dbonkowska.dscribe.llm.ToolSpec;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools a model may call, and the dispatch that runs them.
 *
 * <p>Nothing here throws once construction succeeds. Malformed arguments, an unknown tool name
 * and a handler that blows up all come back as an ordinary tool result whose text says what
 * went wrong, because the model reads that text and usually corrects itself on the next turn.
 * Throwing instead would end a run that was one retry from finishing; only the iteration cap
 * ends a run badly.
 */
public final class Toolbox {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Insertion-ordered: the order tools are offered to the model stays the caller's choice. */
    private final Map<String, Tool<?>> byName = new LinkedHashMap<>();

    public Toolbox(List<Tool<?>> tools) {
        for (Tool<?> tool : tools) {
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
            }
        }
    }

    public List<ToolSpec> specs() {
        return byName.values().stream().map(Tool::spec).toList();
    }

    public Message invoke(ToolCall call) {
        String name = call.function().name();
        Tool<?> tool = byName.get(name);

        if (tool == null) {
            return Message.toolResult(
                    call.id(), "Unknown tool: " + name + ". Available tools: " + byName.keySet());
        }

        try {
            Object arguments = MAPPER.readValue(call.function().arguments(), tool.argumentType());
            return Message.toolResult(call.id(), render(tool.apply(arguments)));
        } catch (RuntimeException e) {
            return Message.toolResult(call.id(), "Tool failed: " + e);
        }
    }

    /** A string result is the model's own medium; anything else goes back as JSON. */
    private static String render(Object result) {
        return result instanceof String text ? text : MAPPER.writeValueAsString(result);
    }
}