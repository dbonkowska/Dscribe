package io.github.dbonkowska.dscribe.conversation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void carriesTextAndAnImageTogetherAsContentParts() {
        Message message = new Message(
                Role.user,
                List.of(
                        new ContentPart("text", "see", null),
                        new ContentPart("image_url", null, new ImageUrl("https://e/x.png"))),
                null,
                null);

        JsonNode content = json(message).get("content");

        assertTrue(content.isArray(), () -> "content must be an array of parts, got: " + content);
        assertEquals(2, content.size());

        JsonNode text = content.get(0);
        assertEquals("text", text.get("type").stringValue());
        assertEquals("see", text.get("text").stringValue());
        assertFalse(text.has("image_url"), () -> "a text part carries no image key: " + text);

        JsonNode image = content.get(1);
        assertEquals("image_url", image.get("type").stringValue());
        assertEquals("https://e/x.png", image.get("image_url").get("url").stringValue());
        assertFalse(image.has("text"), () -> "an image part carries no text key: " + image);
    }

    @Test
    void textIsTheContentOfAPlainTurnAndNullWhereThereAreParts() {
        Message parts =
                new Message(Role.user, List.of(new ContentPart("text", "see", null)), null, null);

        assertEquals("hi", new Message(Role.user, "hi").text());
        assertNull(parts.text(), "a message carrying parts has no plain text");
    }

    @Test
    void readsAStringContentBackFromTheWire() {
        Message turn = MAPPER.readValue("{\"role\":\"assistant\",\"content\":\"42\"}", Message.class);

        assertEquals("42", turn.text());
    }

    private static JsonNode json(Message message) {
        return MAPPER.readTree(MAPPER.writeValueAsString(message));
    }
}