package io.github.dbonkowska.dscribe.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One turn in a conversation, in both directions: what we send and what the provider sends
 * back. The tool fields are null on an ordinary turn and {@code NON_NULL} keeps them off the
 * wire — a {@code tool_calls: null} key is rejected outright by some providers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        Role role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId) {

    /** An ordinary turn: text and nothing else. */
    public Message(Role role, String content) {
        this(role, content, null, null);
    }

    /** The answer to one {@link ToolCall}; the id is what pairs it with the call. */
    public static Message toolResult(String toolCallId, String content) {
        return new Message(Role.tool, content, null, toolCallId);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}