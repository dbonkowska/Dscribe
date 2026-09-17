package io.github.dbonkowska.dscribe.labs.s02e04;

import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one door the model has to an API it learns about at run time.
 *
 * <p>The tool is generic on purpose — the parameters are whatever the API's own help says — so the
 * guards here are all that stand between the model and a call nobody meant it to make. Each failure
 * is silent in a live run: an action outside the allowlist reaches the API and does what it does,
 * and the transcript records it as an ordinary call the model chose.
 *
 * <p>Every "nothing was posted" assertion sits after the call it is about. Paths, actions and
 * parameters are invented and belong to no lesson.
 */
class CallToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = "/x/api";
    private static final List<String> ACTIONS = List.of("find", "open");

    /** A stable id in the shape the API uses: 32 hex characters, invented. */
    private static final String HASH = "0123456789abcdef0123456789abcdef";

    /** One post the fake received. */
    private record Posted(String label, String path, JsonNode body) {}

    /** What the fake was handed, in order. */
    private final List<Posted> posted = new ArrayList<>();

    private final Tool<CallTool.Call> tool = new CallTool(
            (label, path, body) -> {
                posted.add(new Posted(label, path, MAPPER.valueToTree(body)));
                return "{\"reply\":" + posted.size() + "}";
            },
            new CallTool.Spec(PATH, ACTIONS))
            .tool("call", "calls");

    /**
     * The narrowing. Without it the model may name an action that does exist on the API but was
     * left out on purpose — and the provider would happily emit it.
     */
    @Test
    void offersOnlyTheAllowedActions() {
        List<String> allowed = new ArrayList<>();
        tool.spec().function().parameters()
                .at("/properties/action/enum")
                .forEach(node -> allowed.add(node.stringValue()));

        assertEquals(ACTIONS, allowed);
    }

    /** Parameters keep their JSON types: a page number sent as a string is a different request. */
    @Test
    void postsTheParametersWithTheActionToTheConfiguredPath() {
        Object result = tool.handler()
                .apply(new CallTool.Call("find", "{\"q\":\"abc\",\"page\":2}"))
                .result();

        assertEquals(1, posted.size());
        assertEquals(PATH, posted.getFirst().path());
        assertEquals(MAPPER.readTree("{\"q\":\"abc\",\"page\":2,\"action\":\"find\"}"), posted.getFirst().body());
        assertEquals("{\"reply\":1}", result, "the model reads what the API said, word for word");
    }

    @Test
    void postsAnEmptyParameterObjectAsTheActionAlone() {
        tool.handler().apply(new CallTool.Call("find", "{}"));

        assertEquals(MAPPER.readTree("{\"action\":\"find\"}"), posted.getFirst().body());
    }

    /** The schema narrows actions, but a provider is not obliged to honour a schema. */
    @Test
    void refusesAnActionOutsideTheAllowlistWithoutPostingAnything() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new CallTool.Call("wipe", "{}")));

        assertEquals(List.of(), posted, "an action left out on purpose must not reach the API");
        assertTrue(thrown.getMessage().contains("wipe") && thrown.getMessage().contains("find"),
                () -> "the model has to be told what it asked for and what it may ask for: " + thrown.getMessage());
    }

    /**
     * Refused as something the model can fix, not as a cast failure — both reach it as text, but
     * only one says what to send instead.
     */
    @Test
    void refusesParametersThatAreNotAnObjectWithoutPostingAnything() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new CallTool.Call("find", "[1,2]")));

        assertEquals(List.of(), posted);
        assertTrue(thrown.getMessage().contains("object"), thrown::getMessage);
    }

    @Test
    void refusesParametersThatAreNotJsonWithoutPostingAnything() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new CallTool.Call("find", "not json")));

        assertEquals(List.of(), posted);
    }

    /**
     * The smuggled verb. The narrowed action says one thing and the parameters another; merged
     * either way round, the transcript and the API would disagree about what was called — and one
     * order lets the parameters reach an action the allowlist left out.
     */
    @Test
    void refusesAnActionInsideTheParametersWithoutPostingAnything() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new CallTool.Call("find", "{\"action\":\"wipe\"}")));

        assertEquals(List.of(), posted);
        assertTrue(thrown.getMessage().contains("action"), thrown::getMessage);
    }

    /**
     * The positional id. It renumbers on every call, so one copied from an earlier reply names a
     * different item by the time it is sent — and the API answers with that item, without an error.
     * The model then reasons confidently from something it never meant to read.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"ids\":126}",
            "{\"ids\":\"126\"}",
            // the positional id second, so a check that reads only the first element lets it through
            "{\"ids\":[\"" + HASH + "\",126]}"})
    void refusesAPositionalIdWithoutPostingAnything(String params) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new CallTool.Call("open", params)));

        assertEquals(List.of(), posted);
        assertTrue(thrown.getMessage().contains("ids") && thrown.getMessage().contains("32"),
                () -> "the model has to be told which id to use instead: " + thrown.getMessage());
    }

    /**
     * The stable id passes whatever it happens to be made of — including one of all digits, which a
     * rule of "no digit-only ids" would refuse. Only {@code ids} is guarded: a page number is a number.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"ids\":[\"" + HASH + "\"]}",
            "{\"ids\":\"" + HASH + "\"}",
            "{\"ids\":\"12345678901234567890123456789012\"}",
            "{\"page\":3}"})
    void postsStableIdsAndOtherNumbersUnchanged(String params) {
        tool.handler().apply(new CallTool.Call("open", params));

        ObjectNode expected = (ObjectNode) MAPPER.readTree(params);
        expected.put("action", "open");
        assertEquals(List.of(expected), posted.stream().map(Posted::body).toList());
    }

    /** The key is merged in by the client; one written by the model is at best a wrong one. */
    @Test
    void refusesAnApiKeyInsideTheParametersWithoutPostingAnything() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new CallTool.Call("find", "{\"apikey\":\"k\"}")));

        assertEquals(List.of(), posted);
        assertTrue(thrown.getMessage().contains("apikey"), thrown::getMessage);
    }
}
