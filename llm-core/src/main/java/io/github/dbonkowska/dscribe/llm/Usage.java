package io.github.dbonkowska.dscribe.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What one exchange actually cost, as the provider reports it.
 *
 * <p>Not an estimate. {@code Tokens.count} in {@code labs} answers a different question — will
 * this fit, before it is sent — and is a tokeniser's guess by construction. These are the numbers
 * the bill is drawn on.
 *
 * <p>{@code cost} is taken rather than derived. A per-model price table on our side would be
 * recomputing a multiplication the provider has already done, and would be wrong from the moment
 * a price moved — silently, because nothing would disagree with it.
 *
 * <p>Two fields deliberately left on the wire: {@code prompt_tokens_details.cached_tokens} and
 * {@code completion_tokens_details.reasoning_tokens}. Both are reported, both would matter to a
 * run that reuses a prompt or pays for hidden reasoning, and neither has a consumer yet. They are
 * named here so the next lesson that needs one does not have to rediscover that it exists.
 *
 * <p>Absent is not zero. A response carrying no {@code usage} object leaves this null, and a
 * reader has to be able to tell "reported nothing" from "cost nothing".
 */
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens,
        double cost) {}
