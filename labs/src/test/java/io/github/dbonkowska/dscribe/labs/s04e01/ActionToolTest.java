package io.github.dbonkowska.dscribe.labs.s04e01;

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
 * The one door the model has to an endpoint whose every action is a write. The tool is generic on
 * purpose — the parameters are whatever the endpoint's own help says — so the refusals here are all
 * that stand between a malformed call and the hub. Each is silent in a live run: a call that reached
 * the hub is recorded as one the model chose, and its effect stays.
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

    /**
     * The vocabulary is discovered at run time, so nothing narrows the action: an allowlist copied
     * in from s02e04 would refuse every action the endpoint really has.
     */
    @Test
    void sendsAnyActionTheModelNames(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);
        tool.handler().apply(new ActionTool.Call("anything-at-all", "{}"));

        assertEquals(MAPPER.readTree("{\"action\":\"anything-at-all\"}"), sent.getFirst().answer());
        assertTrue(tool.spec().function().parameters().at("/properties/action/enum").isMissingNode(),
                "the schema must not narrow an action nobody knows yet");
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
     * either way round, the transcript's label and the write the hub makes would disagree — and here
     * the write is the one that stays.
     */
    @Test
    void refusesAnActionInsideTheParametersWithoutSendingAnything(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ActionTool.Call("list", "{\"action\":\"done\"}")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("action"), thrown::getMessage);
    }

    @Test
    void sendsAWriteToAPageTheBundleAllows(@TempDir Path root) {
        tool(root).handler().apply(new ActionTool.Call("update", "{\"page\":\"front\",\"id\":\"x\"}"));

        assertEquals(MAPPER.readTree("{\"page\":\"front\",\"id\":\"x\",\"action\":\"update\"}"), sent.getFirst().answer());
    }

    /**
     * The write that happened. A model that could not read a record in full overwrote it, hoping the
     * reply would show what was there — and the record held the one thing the run needed. The pages
     * a run may change are the exercise's to say, so any other page is refused before it is sent.
     */
    @Test
    void refusesAWriteToAPageTheBundleDoesNotAllowWithoutSendingAnything(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ActionTool.Call("update", "{\"page\":\"other\",\"id\":\"x\"}")));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("other") && thrown.getMessage().contains("front")
                        && thrown.getMessage().contains("notes"),
                () -> "the model has to be told what it asked for and what it may write: " + thrown.getMessage());
    }

    /** A page that is not a name cannot be one of the allowed names, and is not waved through as one. */
    @Test
    void refusesAPageThatIsNotAStringWithoutSendingAnything(@TempDir Path root) {
        Tool<ActionTool.Call> tool = tool(root);

        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ActionTool.Call("update", "{\"page\":3}")));

        assertEquals(List.of(), sent);
    }

    private Tool<ActionTool.Call> tool(Path root) {
        return new ActionTool(hub(root), Set.of("front", "notes")).tool("act", "does one action");
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
