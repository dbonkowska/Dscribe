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
 * <p>The payloads carry fields we never read ({@code id}, {@code refusal}, and the nested detail
 * objects inside {@code usage}) — providers add them freely, and the mapper below is configured
 * the way {@link LlmClient} configures its own, which is what lets them through. That tolerance
 * is load-bearing rather than incidental: {@code usage} arrives wrapped in three nested objects
 * we bind none of, so re-enabling {@code FAIL_ON_UNKNOWN_PROPERTIES} anywhere would take the
 * spend record down with it.
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

    private static final String USAGE_PAYLOAD = """
            {
              "id": "gen-3",
              "model": "vendor/served",
              "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 20,
                "total_tokens": 120,
                "cost": 0.0009
              },
              "choices": [
                {
                  "finish_reason": "stop",
                  "message": {"role": "assistant", "content": "ok"}
                }
              ]
            }
            """;

    /**
     * The shape a provider actually sends: three nested detail objects beside the four numbers we
     * bind. Invented values, real structure.
     */
    private static final String WIRE_USAGE_PAYLOAD = """
            {
              "id": "gen-4",
              "model": "vendor/served",
              "provider": "Vendor",
              "usage": {
                "prompt_tokens": 1342,
                "completion_tokens": 85,
                "total_tokens": 1427,
                "cost": 0.000926,
                "is_byok": false,
                "prompt_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
                "cost_details": {"upstream_inference_cost": 0.000926},
                "completion_tokens_details": {"reasoning_tokens": 0}
              },
              "choices": [
                {
                  "finish_reason": "stop",
                  "message": {"role": "assistant", "content": "ok"}
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

    /**
     * The invoice, not an estimate. These are the provider's own counts and its own price for the
     * call — a component name that stops matching the wire leaves a zero, and a zero here reads
     * as a free call rather than as a parsing fault.
     */
    @Test
    void readsTokensAndCostOffTheResponse() {
        ChatResponse response = MAPPER.readValue(USAGE_PAYLOAD, ChatResponse.class);

        assertEquals("vendor/served", response.model());
        assertEquals(100, response.usage().promptTokens());
        assertEquals(20, response.usage().completionTokens());
        assertEquals(120, response.usage().totalTokens());
        assertEquals(0.0009, response.usage().cost());
    }

    @Test
    void readsUsageOutOfThePayloadTheProviderActuallySends() {
        Usage usage = MAPPER.readValue(WIRE_USAGE_PAYLOAD, ChatResponse.class).usage();

        assertEquals(1342, usage.promptTokens());
        assertEquals(85, usage.completionTokens());
        assertEquals(1427, usage.totalTokens());
        assertEquals(0.000926, usage.cost());
    }

    /**
     * Absent rather than zero. A provider that reports nothing must not be recorded as having
     * cost nothing, and the caller has to be able to tell the two apart.
     */
    @Test
    void leavesUsageNullWhenTheProviderReportsNone() {
        assertNull(MAPPER.readValue(TEXT_PAYLOAD, ChatResponse.class).usage());
    }

    private static ChatResponse.Choice firstChoice(String payload) {
        return MAPPER.readValue(payload, ChatResponse.class).choices().getFirst();
    }
}