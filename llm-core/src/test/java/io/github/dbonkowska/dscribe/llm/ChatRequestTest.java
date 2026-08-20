package io.github.dbonkowska.dscribe.llm;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request is snake_case on the wire and camelCase in Java, and nothing but this test
 * notices when the two drift: a mistyped {@code @JsonProperty} still compiles and the
 * provider simply ignores the key it does not recognise, silently dropping structured output.
 *
 * <p>Absence matters as much as presence — a {@code response_format: null} key is rejected
 * outright rather than ignored, so the omission is asserted too.
 */
class ChatRequestTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final Object SCHEMA = Map.of("type", "object");

    private static final List<Message> MESSAGES = List.of(new Message(Role.user, "hi"));

    @Test
    void namesTheResponseFormatKeysTheWayTheProviderSpellsThem() {
        ChatRequest request = new ChatRequest("m", MESSAGES, ResponseFormat.jsonSchema("r", SCHEMA));

        assertEquals("json_schema", request.responseFormat().type());
        assertEquals("r", request.responseFormat().jsonSchema().name());

        JsonNode json = json(request);
        assertTrue(json.has("response_format"), () -> "expected response_format in: " + json);
        assertTrue(
                json.get("response_format").has("json_schema"),
                () -> "expected json_schema in: " + json);
    }

    @Test
    void omitsResponseFormatEntirelyWhenThereIsNone() {
        JsonNode json = json(new ChatRequest("m", MESSAGES, null));

        assertFalse(json.has("response_format"), () -> "expected no response_format in: " + json);
        assertEquals("m", json.get("model").stringValue());
    }

    @Test
    void offersEveryToolAlongsideTheChoiceOfWhetherToUseThem() {
        ChatRequest request = new ChatRequest(
                "m", MESSAGES, null, List.of(spec("lookup"), spec("count")), "auto");

        JsonNode json = json(request);

        assertEquals(2, json.get("tools").size(), () -> "expected two tools in: " + json);
        assertEquals("lookup", json.get("tools").get(0).get("function").get("name").stringValue());
        assertEquals("auto", json.get("tool_choice").stringValue());
    }

    @Test
    void omitsToolKeysEntirelyWhenThereAreNoTools() {
        // a tools: null key is rejected rather than ignored, so absence has to be total
        JsonNode json = json(new ChatRequest("m", MESSAGES, null));

        assertFalse(json.has("tools"), () -> "expected no tools in: " + json);
        assertFalse(json.has("tool_choice"), () -> "expected no tool_choice in: " + json);
    }

    private static ToolSpec spec(String name) {
        return ToolSpec.function(name, "finds things", MAPPER.createObjectNode());
    }

    private static JsonNode json(ChatRequest request) {
        return MAPPER.readTree(MAPPER.writeValueAsString(request));
    }
}