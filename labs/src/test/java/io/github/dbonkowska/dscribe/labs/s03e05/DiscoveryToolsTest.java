package io.github.dbonkowska.dscribe.labs.s03e05;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.HubSend;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.s03e05.TaskParams.ToolPrompt;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the three tools send, and where. The registry decides which names resolve; these tests pin
 * that a tool only ever posts to an address the registry handed back, and that nothing the hub says
 * is rewritten on its way to the model.
 *
 * <p>Fixtures are invented — the paths, names and replies belong to no lesson.
 */
class DiscoveryToolsTest {

    private static final String SEARCH_PATH = "/api/x-search";
    private static final String FOUND = "{\"tools\":[{\"name\":\"alpha\",\"url\":\"/api/alpha\","
            + "\"parameter\":\"query\"}]}";

    private static final TaskParams TASK = new TaskParams(
            "x-task",
            SEARCH_PATH,
            "[{]F[}]",
            new ToolPrompt("find", "finds tools"),
            new ToolPrompt("use", "uses a found tool"),
            new ToolPrompt("send", "sends the answer"));

    /** One post the fake hub received. */
    record Posted(String label, String path, Object body) {}

    private final List<Posted> posted = new ArrayList<>();
    private final List<Object> verified = new ArrayList<>();

    /** Replies to a search with {@link #searchReply}, and to anything else with its own path. */
    private String searchReply = FOUND;

    @Test
    void refusesToCallANameNoSearchHasFound(@TempDir Path root) {
        String reply = text(tools(root).call().handler()
                .apply(new DiscoveryTools.Call("alpha", "q")));

        assertEquals(List.of(), posted, "an unknown name must not reach the hub");
        assertTrue(reply.contains("alpha"), reply);
        assertTrue(reply.contains("find"), () -> "it has to name the search tool to use: " + reply);
    }

    /** The list is what tells the model which name it probably meant. */
    @Test
    void listsTheKnownNamesWhenRefusingAnUnknownOne(@TempDir Path root) {
        DiscoveryTools tools = tools(root);
        tools.search().handler().apply(new DiscoveryTools.Search("q1"));
        int before = posted.size();

        String reply = text(tools.call().handler().apply(new DiscoveryTools.Call("gamma", "q")));

        assertEquals(before, posted.size(), "an unknown name must not reach the hub");
        assertTrue(reply.contains("alpha"), reply);
    }

    /**
     * "Search first" would be untrue here, since the search did find it, and it would send the
     * model round a loop of paid turns that end in the same refusal.
     */
    @Test
    void givesTheReasonWhenCallingAToolTheSearchFoundButWasRefused(@TempDir Path root) {
        searchReply = "{\"tools\":[{\"name\":\"beta\",\"url\":\"https://evil.example/b\","
                + "\"parameter\":\"query\"}]}";
        DiscoveryTools tools = tools(root);
        tools.search().handler().apply(new DiscoveryTools.Search("q1"));
        int before = posted.size();

        String reply = text(tools.call().handler().apply(new DiscoveryTools.Call("beta", "q")));

        assertEquals(before, posted.size(), "a refused tool must not reach the hub");
        assertTrue(reply.contains("https://evil.example/b"), reply);
        assertTrue(!reply.contains("first"), () -> "it must not send the model back to search: " + reply);
    }

    @Test
    void searchesAtTheConfiguredPathAndReturnsTheReplyUntouched(@TempDir Path root) {
        String reply = text(tools(root).search().handler()
                .apply(new DiscoveryTools.Search("q1")));

        assertEquals(List.of(new Posted("find", SEARCH_PATH, Map.of("query", "q1"))), posted);
        assertEquals(FOUND, reply);
    }

    @Test
    void callsAFoundToolAtTheAddressTheSearchGave(@TempDir Path root) {
        DiscoveryTools tools = tools(root);
        tools.search().handler().apply(new DiscoveryTools.Search("q1"));

        String reply = text(tools.call().handler()
                .apply(new DiscoveryTools.Call("alpha", "q2")));

        assertEquals(new Posted("alpha", "/api/alpha", Map.of("query", "q2")), posted.getLast());
        assertEquals("reply from /api/alpha", reply);
    }

    /** Without the reason, the model would see the name in the reply and not know why it fails. */
    @Test
    void addsARefusalBelowTheReplyForAToolItWillNotCall(@TempDir Path root) {
        searchReply = "{\"tools\":[{\"name\":\"beta\",\"url\":\"https://evil.example/b\","
                + "\"parameter\":\"query\"}]}";

        String reply = text(tools(root).search().handler()
                .apply(new DiscoveryTools.Search("q1")));

        assertTrue(reply.startsWith(searchReply), reply);
        assertTrue(reply.substring(searchReply.length()).contains("beta"), reply);
        assertTrue(reply.substring(searchReply.length()).contains("https://evil.example/b"), reply);
    }

    @Test
    void submitsTheAnswerToVerifyAndReturnsTheReplyUntouched(@TempDir Path root) {
        String reply = text(tools(root).submit().handler()
                .apply(new DiscoveryTools.Submit(List.of("a", "b"))));

        assertEquals(List.of(List.of("a", "b")), verified);
        assertEquals("{\"verdict\":1}", reply);
    }

    private DiscoveryTools tools(Path root) {
        Post post = (label, path, body) -> {
            posted.add(new Posted(label, path, body));
            return path.equals(SEARCH_PATH) ? searchReply : "reply from " + path;
        };
        HubSend sender = (label, taskName, answer) -> {
            verified.add(answer);
            return new HubResponse(200, TestHeaders.of(Map.of()), "{\"verdict\":" + verified.size() + "}");
        };
        ResilientHub verify = new ResilientHub(
                sender,
                "x-task",
                RetryPolicy.defaults(),
                new RateLimitHeaders(List.of(), Duration.ofSeconds(60)),
                wait -> {},
                RunTranscript.open(root.resolve("logs"), "x01", Map.of(), List.of()));

        return new DiscoveryTools(post, verify, TASK);
    }

    private static String text(ToolOutput output) {
        return (String) output.result();
    }
}
