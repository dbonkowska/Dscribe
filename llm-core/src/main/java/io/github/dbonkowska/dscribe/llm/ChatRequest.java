package io.github.dbonkowska.dscribe.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.dbonkowska.dscribe.conversation.Message;

import java.util.List;

/**
 * Wire names sit on {@code @JsonProperty} rather than coming from a global {@code SNAKE_CASE}
 * naming strategy: the same mapper serialises caller-supplied result records, whose schemas
 * are generated from camelCase components, and a global strategy would rename those too.
 *
 * @param toolChoice {@code auto}, {@code none} or {@code required}. A plain string, because
 *                   naming one specific function needs an object shape and nothing wants it yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<Message> messages,
        @JsonProperty("response_format") ResponseFormat responseFormat,
        List<ToolSpec> tools,
        @JsonProperty("tool_choice") String toolChoice) {

    /** A request with no tools in play — structured output, or plain text. */
    public ChatRequest(String model, List<Message> messages, ResponseFormat responseFormat) {
        this(model, messages, responseFormat, null, null);
    }
}