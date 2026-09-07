package io.github.dbonkowska.dscribe.llm;

import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.conversation.ToolCall;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The response is where provider JSON becomes Java, and every mistake in it is silent: a
 * component whose name no longer matches the wire deserialises to null, so a dropped
 * {@code tool_calls} array or a null {@code finish_reason} reads as "the model just answered"
 * and an agent loop spins until its iteration cap instead of failing.
 *
 * <p>The payloads carry fields we never read ({@code id}, {@code usage}, {@code refusal}) —
 * providers add them freely, and the mapper below is configured the way {@link LlmClient}
 * configures its own, which is what lets them through.
 */
class ChatResponseTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final String TOOL_CALL_PAYLOAD = """
            {
              "id": "gen-1",
              "usage": {"total_tokens": 12},
              "choices": [
                {
                  "finish_reason": "tool_calls",
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "refusal": null,
                    "tool_calls": [
                      {
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "lookup", "arguments": "{\\"q\\":\\"x\\"}"}
                      }
                    ]
                  }
                }
              ]
            }
            """;

    private static final String TEXT_PAYLOAD = """
            {
              "id": "gen-2",
              "choices": [
                {
                  "finish_reason": "stop",
                  "message": {"role": "assistant", "content": "42"}
                }
              ]
            }
            """;

    @Test
    void readsToolCallsAndFinishReasonOffAnAssistantTurn() {
        ChatResponse.Choice choice = firstChoice(TOOL_CALL_PAYLOAD);

        assertEquals("tool_calls", choice.finishReason());
        assertEquals(Role.assistant, choice.message().role());
        assertNull(choice.message().text());

        assertEquals(1, choice.message().toolCalls().size());
        ToolCall call = choice.message().toolCalls().getFirst();
        assertEquals("call_1", call.id());
        assertEquals("function", call.type());
        assertEquals("lookup", call.function().name());
        assertEquals("{\"q\":\"x\"}", call.function().arguments());
    }

    @Test
    void readsAPlainTextTurnAsContentWithNoToolCalls() {
        ChatResponse.Choice choice = firstChoice(TEXT_PAYLOAD);

        assertEquals("stop", choice.finishReason());
        assertEquals("42", choice.message().text());
        assertFalse(choice.message().hasToolCalls());
    }

    private static ChatResponse.Choice firstChoice(String payload) {
        return MAPPER.readValue(payload, ChatResponse.class).choices().getFirst();
    }
}