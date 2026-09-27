package io.github.dbonkowska.dscribe.labs.s04e03;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model's only door to an environment where most actions cost budget and one of them wipes
 * everything. The tool is generic on purpose — the parameters are whatever the endpoint's own help
 * says — so the refusals here are all that stand between a malformed or forbidden call and the hub.
 *
 * <p>Every "nothing was sent" assertion sits after the call it is about. Actions and parameters are
 * invented and belong to no lesson.
 */
class ActionToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One send the fake hub received. */
    private record Sent(String label, JsonNode answer) {}

    /** What the fake hub was handed, in order. */
    private final List<Sent> sent = new ArrayList<>();

    /** Parameters keep their JSON types: a number sent as a string is a different request. */
    @Test
    void sendsTheParametersWithTheActionAsTheAnswer(@TempDir Path root) {
        tool(root).handler().apply(new ActionTool.Call("list", "{\"n\":2,\"q\":\"x\"}"));

        assertEquals(1, sent.size());
        assertEquals(MAPPER.readTree("{\"n\":2,\"q\":\"x\",\"action\":\"list\"}"), sent.getFirst().answer());
        assertTrue(sent.getFirst().label().contains("list"),
                () -> "the transcript has to read per action: " + sent.getFirst().label());
    }

    @Test
    void sendsAnEmptyParameterObjectAsTheActionAlone(@TempDir Path root) {
        tool(root).handler().apply(new ActionTool.Call("help", "{}"));

        assertEquals(MAPPER.readTree("{\"action\":\"help\"}"), sent.getFirst().answer());
    }

    /** The observer and the model both read this, so it must be what the hub said and nothing more. */
    @Test
    void handsBackTheHubsReplyWordForWord(@TempDir Path root) {
        Object result = tool(root).handler().apply(new ActionTool.Call("help", "{}")).result();

        assertEquals("{\"n\":1}", result);
    }

    /** The vocabulary is discovered at run time, so nothing narrows the action. */
    @Test
    void sendsAnyActionTheModelNames(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);
        tool.handler().apply(new ActionTool.Call("anything-at-all", "{}"));

        assertEquals(MAPPER.readTree("{\"action\":\"anything-at-all\"}"), sent.getFirst().answer());
        assertTrue(tool.spec().function().parameters().at("/properties/action/enum").isMissingNode(),
                "the schema must not narrow an action nobody knows yet");
    }

    /**
     * s04e01's tool fenced writes to allowed pages. Nothing here is a page, so a parameter that
     * happens to be called that goes through untouched.
     */
    @Test
    void sendsAPageShapedParameterUntouched(@TempDir Path root) {
        tool(root).handler().apply(new ActionTool.Call("move", "{\"page\":\"any\"}"));

        assertEquals(MAPPER.readTree("{\"page\":\"any\",\"action\":\"move\"}"), sent.getFirst().answer());
    }

    @Test
    void refusesParametersThatAreNotJsonWithoutSendingAnything(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);

        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ActionTool.Call("list", "not json")));

        assertEquals(List.of(), sent);
    }

    /**
     * Refused as something the model can fix, not as a cast failure — both reach it as text, but only
     * one says what to send instead.
     */
    @Test
    void refusesParametersThatAreNotAnObjectWithoutSendingAnything(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ActionTool.Call("list", "[1,2]")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("object"), thrown::getMessage);
    }

    /**
     * The smuggled verb. The action argument says one thing and the parameters another; merged
     * either way round, the transcript's label and the action the hub takes would disagree. Here
     * it is also the way round the refused-actions check.
     */
    @Test
    void refusesAnActionInsideTheParametersWithoutSendingAnything(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ActionTool.Call("list", "{\"action\":\"wipe\"}")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("action"), thrown::getMessage);
    }

    /**
     * The reset wipes every unit and moves the target, so everything the model learned about the
     * board is gone. The run sends it once at startup; the model never does.
     */
    @Test
    void refusesARefusedActionWithoutSendingAnything(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ActionTool.Call("wipe", "{}")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("wipe") && thrown.getMessage().contains("startup"),
                () -> "the model has to be told what was refused and that the run already did it: "
                        + thrown.getMessage());
    }

    /**
     * Decided by the action before the parameters are read, so the refusal names the real reason.
     * A bad-JSON refusal here would invite the model to fix the JSON and send the reset again.
     */
    @Test
    void refusesARefusedActionBeforeReadingItsParameters(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ActionTool.Call("wipe", "not json")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("startup"),
                () -> "refused as a reserved action, not as bad JSON: " + thrown.getMessage());
    }

    private Tool<ActionTool.Call> tool(Path root) {
        return new ActionTool(hub(root), Set.of("wipe")).tool("act", "does one action");
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
