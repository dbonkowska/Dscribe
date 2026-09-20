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
 *
 * @param model what the provider says answered, which is not always what was asked for — it may
 *              route elsewhere, and spend belongs to the model that produced the turn
 * @param usage the provider's own token counts and price for this exchange; null when it reports
 *              none, which is not the same as nothing having been spent
 */
public record ChatResponse(List<Choice> choices, String model, Usage usage) {

    public record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {}
}