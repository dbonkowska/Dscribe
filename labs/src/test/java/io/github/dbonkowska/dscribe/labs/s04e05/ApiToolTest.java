package io.github.dbonkowska.dscribe.labs.s04e05;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.HubSend;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model's only door to an environment where it owns every read and every write. The tool is
 * generic on purpose — which tools and actions exist is whatever the endpoint's own help says — so
 * the refusals here are all that stand between a malformed call and the hub.
 *
 * <p>Every "nothing was sent" assertion sits after the call it is about. Tools, actions and
 * parameters are invented and belong to no lesson.
 */
class ApiToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One send the fake hub received. */
    private record Sent(String label, JsonNode answer) {}

    /** What the fake hub was handed, in order. */
    private final List<Sent> sent = new ArrayList<>();

    /** Parameters keep their JSON types: a number sent as a string is a different request. */
    @Test
    void sendsTheParametersWithToolAndActionAsTheAnswer(@TempDir Path root) {
        tool(root).handler().apply(new ApiTool.Call("store", "list", "{\"n\":2}"));

        assertEquals(1, sent.size());
        assertEquals(MAPPER.readTree("{\"n\":2,\"tool\":\"store\",\"action\":\"list\"}"), sent.getFirst().answer());
        assertTrue(sent.getFirst().label().contains("store") && sent.getFirst().label().contains("list"),
                () -> "the transcript has to read per tool and action: " + sent.getFirst().label());
    }

    /** Some tools take no action at all; an empty one sent along would be a different request. */
    @Test
    void omitsABlankAction(@TempDir Path root) {
        tool(root).handler().apply(new ApiTool.Call("info", "  ", "{}"));

        assertEquals(MAPPER.readTree("{\"tool\":\"info\"}"), sent.getFirst().answer());
    }

    /** The observer and the model both read this, so it must be what the hub said and nothing more. */
    @Test
    void handsBackTheHubsReplyWordForWord(@TempDir Path root) {
        Object result = tool(root).handler().apply(new ApiTool.Call("info", "", "{}")).result();

        assertEquals("{\"n\":1}", result);
    }

    /** The vocabulary is discovered at run time, so nothing narrows the tool or the action. */
    @Test
    void narrowsNeitherToolNorAction(@TempDir Path root) {
        JsonNode parameters = tool(root).spec().function().parameters();

        assertTrue(parameters.at("/properties/tool/enum").isMissingNode(), "tool must not be narrowed");
        assertTrue(parameters.at("/properties/action/enum").isMissingNode(), "action must not be narrowed");
    }

    @Test
    void refusesParametersThatAreNotJsonWithoutSendingAnything(@TempDir Path root) {
        Tool<ApiTool.Call> tool = tool(root);

        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ApiTool.Call("store", "list", "not json")));

        assertEquals(List.of(), sent);
    }

    /**
     * Refused as something the model can fix, not as a cast failure — both reach it as text, but only
     * one says what to send instead.
     */
    @Test
    void refusesParametersThatAreNotAnObjectWithoutSendingAnything(@TempDir Path root) {
        Tool<ApiTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ApiTool.Call("store", "list", "[1]")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("object"), thrown::getMessage);
    }

    /**
     * The smuggled key. The argument says one thing and the parameters another; merged either way
     * round, the transcript's label and what the hub does would disagree — and a tool named inside
     * the parameters would be the way round every check on the argument.
     */
    @ParameterizedTest
    @ValueSource(strings = {"tool", "action"})
    void refusesAReservedKeyInsideTheParametersWithoutSendingAnything(String key, @TempDir Path root) {
        Tool<ApiTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ApiTool.Call("store", "list", "{\"" + key + "\":\"x\"}")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains(key) && thrown.getMessage().contains("must not contain"),
                thrown::getMessage);
    }

    /**
     * The reset restores the seeded orders, so every order the model created is gone. The run sends
     * it once at startup; the model never does.
     */
    @Test
    void refusesTheResetWithoutSendingAnything(@TempDir Path root) {
        Tool<ApiTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ApiTool.Call("wipe", "", "{}")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("wipe") && thrown.getMessage().contains("startup"),
                () -> "the model has to be told what was refused and that the run already did it: "
                        + thrown.getMessage());
    }

    /**
     * Decided by the tool before the parameters are read, so the refusal names the real reason. A
     * bad-JSON refusal here would invite the model to fix the JSON and send the reset again.
     */
    @Test
    void refusesTheResetBeforeReadingItsParameters(@TempDir Path root) {
        Tool<ApiTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ApiTool.Call("wipe", "", "not json")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("startup"),
                () -> "refused as the reset, not as bad JSON: " + thrown.getMessage());
    }

    /**
     * Matched stripped and case-folded, the way s04e03's environment matched its reset. Whether this
     * one folds is unprobed; refusing a spelling it would have rejected anyway costs nothing, and
     * letting through one it accepts would wipe the run.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Wipe", "WIPE", " wipe", "wipe ", "\twipe\n"})
    void refusesTheResetUnderAnySpelling(String spelling, @TempDir Path root) {
        Tool<ApiTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ApiTool.Call(spelling, "", "{}")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("startup"), thrown::getMessage);
    }

    /** Folding is for matching the reset, not a licence to refuse names that merely contain it. */
    @Test
    void sendsAToolThatOnlyContainsTheResetsName(@TempDir Path root) {
        tool(root).handler().apply(new ApiTool.Call("wipeLogs", "", "{}"));

        assertEquals(1, sent.size());
    }

    private Tool<ApiTool.Call> tool(Path root) {
        return new ApiTool(hub(root), "wipe").tool("call", "calls one tool");
    }

    private ResilientHub hub(Path root) {
        HubSend sender = (label, taskName, answer) -> {
            sent.add(new Sent(label, MAPPER.valueToTree(answer)));
            return new HubResponse(200, TestHeaders.of(Map.of()), "{\"n\":" + sent.size() + "}");
        };

        return new ResilientHub(
                sender,
                "x-task",
                RetryPolicy.defaults(),
                new RateLimitHeaders(List.of(), Duration.ofSeconds(60)),
                wait -> {},
                RunTranscript.open(root.resolve("logs"), "x01", Map.of(), List.of()));
    }
}
