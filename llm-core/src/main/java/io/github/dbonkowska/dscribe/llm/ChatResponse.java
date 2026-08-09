package io.github.dbonkowska.dscribe.llm;

import java.util.List;

/**
 * Only the fields we read. Unknown ones are ignored — see the mapper in {@link LlmClient}.
 */
public record ChatResponse(List<Choice> choices) {
    public record Choice(Message message) {}
    public record Message(String content) {}
}