package io.github.dbonkowska.dscribe.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.dbonkowska.dscribe.conversation.Message;

import java.util.List;

/**
 * Only the fields we read. Unknown ones are ignored — see the mapper in {@link LlmClient}.
 *
 * <p>The turn comes back as the same {@link Message} we send, so an agent loop can append an
 * assistant turn to its transcript verbatim instead of copying it field by field — which is
 * how a {@code tool_calls} array gets lost on the way back to the provider.
 */
public record ChatResponse(List<Choice> choices) {

    public record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {}
}