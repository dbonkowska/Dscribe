package io.github.dbonkowska.dscribe.llm;

import io.github.dbonkowska.dscribe.conversation.Message;

import java.util.List;

/**
 * One round-trip to a model, and the whole of what an agent loop needs from a provider.
 *
 * <p>Narrow on purpose: it keeps the loop testable without HTTP, a stub server or a mocking
 * framework — a fake that returns queued choices is a few lines.
 */
public interface ChatTransport {

    ChatResponse.Choice send(List<Message> messages, List<ToolSpec> tools, String toolChoice);
}