package io.github.dbonkowska.dscribe.conversation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the wire shape of a tool-calling turn. Every failure mode here is a provider-side
 * 400 or a silently dropped call rather than a Java error: a {@code tool_calls: null} key on
 * an ordinary message, a {@code tool_call_id} that goes missing, or {@code arguments}
 * re-serialised as a nested object instead of the string the provider sent.
 *
 * <p>The fixture tool is invented — {@code lookup} belongs to no lesson.
 */
class MessageTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String ARGUMENTS = "{\"q\":\"x\"}";

    @Test
    void aPlainTurnSerialisesWithoutAnyToolKeys() {
        assertEquals(
                "{\"role\":\"user\",\"content\":\"hi\"}",
                MAPPER.writeValueAsString(new Message(Role.user, "hi")));
    }

    @Test
    void keepsToolCallArgumentsAsTheRawJsonStringTheProviderSent() {
        Message assistant = new Message(
                Role.assistant,
                null,
                List.of(new ToolCall("call_1", "function", new ToolCall.Invocation("lookup", ARGUMENTS))),
                null);

        JsonNode call = json(assistant).get("tool_calls").get(0);

        assertEquals("call_1", call.get("id").stringValue());
        assertEquals("function", call.get("type").stringValue());
        assertEquals("lookup", call.get("function").get("name").stringValue());

        JsonNode arguments = call.get("function").get("arguments");
        assertTrue(arguments.isString(), () -> "arguments must stay a string, got: " + arguments);
        assertEquals(ARGUMENTS, arguments.stringValue());
    }

    @Test
    void aToolResultNamesTheCallItAnswers() {
        assertEquals(
                "{\"role\":\"tool\",\"content\":\"42\",\"tool_call_id\":\"call_1\"}",
                MAPPER.writeValueAsString(Message.toolResult("call_1", "42")));
    }

    @Test
    void hasToolCallsIsTrueOnlyForANonEmptyList() {
        ToolCall call = new ToolCall("call_1", "function", new ToolCall.Invocation("lookup", ARGUMENTS));

        assertFalse(new Message(Role.assistant, "just text").hasToolCalls());
        assertFalse(new Message(Role.assistant, null, List.of(), null).hasToolCalls());
        assertTrue(new Message(Role.assistant, null, List.of(call), null).hasToolCalls());
    }

    private static JsonNode json(Message message) {
        return MAPPER.readTree(MAPPER.writeValueAsString(message));
    }
}