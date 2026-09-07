package io.github.dbonkowska.dscribe.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One turn in a conversation, in both directions: what we send and what the provider sends
 * back. The tool fields are null on an ordinary turn and {@code NON_NULL} keeps them off the
 * wire — a {@code tool_calls: null} key is rejected outright by some providers.
 *
 * <p>{@code content} is {@code Object} because the wire field is two shapes: a string on an
 * ordinary turn, and a {@code List<ContentPart>} on one carrying an image. Modelling that as a
 * value type would be tidier in Java and worse here — every caller reading plain text would pay
 * for a wrapper, and the mapper stays declarative in both directions this way. Only messages we
 * build ever carry parts; the model answers in text, so nothing deserialises into them.
 *
 * <p>Use {@link #text()} rather than {@link #content()} unless the parts are the point.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        Role role,
        Object content,
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

    /**
     * The text of this turn, or null where it carries parts instead.
     *
     * <p>What almost every caller wants. {@link #content()} is the raw wire value and is
     * {@code Object} because the field genuinely is two shapes; reach for it only when the
     * parts themselves are the point.
     */
    public String text() {
        return content instanceof String text ? text : null;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}