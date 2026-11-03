package io.github.dbonkowska.dscribe.labs.s05e03;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model's door to a shell on someone else's machine. The guard decides what may go; this checks
 * that it is asked before anything is sent, that what comes back is the hub's reply and nothing else,
 * and that no credential of the session rides back out to the provider inside the output.
 *
 * <p>Every "nothing was sent" assertion sits after the call it is about. Commands, replies and
 * secrets are invented.
 */
class ShellToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What the fake hub was handed, in order. */
    private final List<JsonNode> sent = new ArrayList<>();

    private int status = 200;
    private String reply = "{\"code\":100,\"output\":\"a\\nb\\n\"}";

    @Test
    void sendsTheCommandAsTheAnswer(@TempDir Path root) {
        tool(root, List.of()).handler().apply(new ShellTool.Call("ls -la"));

        assertEquals(List.of(MAPPER.readTree("{\"cmd\":\"ls -la\"}")), sent);
    }

    /** The model reads the envelope the hub wrote — code, message, output — so it is passed on whole. */
    @Test
    void handsBackTheHubsReplyWordForWord(@TempDir Path root) {
        Object result = tool(root, List.of()).handler().apply(new ShellTool.Call("ls")).result();

        assertEquals(reply, result);
    }

    @Test
    void refusesWhatTheGuardRefusesWithoutSendingIt(@TempDir Path root) {
        Tool<ShellTool.Call> tool = tool(root, List.of());

        assertThrows(IllegalArgumentException.class, () -> tool.handler().apply(new ShellTool.Call("ls; rm x")));

        assertTrue(sent.isEmpty(), () -> "sent: " + sent);
    }

    /**
     * The output is a third party's text and goes to the model provider on every later turn, so a
     * credential the server printed is replaced before it leaves — the same list the transcript uses.
     */
    @Test
    void redactsEverySecretTheServerPrinted(@TempDir Path root) {
        reply = "{\"output\":\"key=s3cr3t other=t0k3n\"}";

        String result = (String) tool(root, List.of("s3cr3t", "t0k3n")).handler().apply(new ShellTool.Call("cat f")).result();

        assertFalse(result.contains("s3cr3t") || result.contains("t0k3n"), result);
        assertEquals("{\"output\":\"key=[redacted] other=[redacted]\"}", result);
    }

    /** Replacing "" matches between every character and would turn the reply into stars. */
    @Test
    void leavesTheReplyAloneForABlankSecret(@TempDir Path root) {
        Object result = tool(root, List.of("", "  ")).handler().apply(new ShellTool.Call("ls")).result();

        assertEquals(reply, result);
    }

    /**
     * Too much output is refused by the hub itself and the refusal names its cap, so it is the model's
     * to act on: returned as the result, not thrown as a failure.
     */
    @Test
    void handsBackARefusalFromTheHubAsTheResult(@TempDir Path root) {
        status = 400;
        reply = "{\"code\":-860,\"message\":\"Output is too large.\"}";

        Object result = tool(root, List.of()).handler().apply(new ShellTool.Call("cat f")).result();

        assertEquals(reply, result);
    }

    /** One free-text argument; nothing about a command can be narrowed into a schema. */
    @Test
    void offersOneCommandArgumentAndNoEnum(@TempDir Path root) {
        JsonNode parameters = tool(root, List.of()).spec().function().parameters();

        assertEquals(List.of("cmd"), parameters.get("properties").propertyNames().stream().toList());
        assertFalse(parameters.toString().contains("enum"), parameters::toString);
    }

    private Tool<ShellTool.Call> tool(Path root, List<String> secrets) {
        return new ShellTool(hub(root), secrets).tool("run", "runs one command");
    }

    private ResilientHub hub(Path root) {
        HubSend sender = (label, taskName, answer) -> {
            sent.add(MAPPER.valueToTree(answer));
            return new HubResponse(status, TestHeaders.of(Map.of()), reply);
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
