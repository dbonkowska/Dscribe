package io.github.dbonkowska.dscribe.labs.s02e02;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.HubSend;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.tool.Tool;
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
 * The guard on the currency this lesson actually spends. Every move is one call against a budget
 * the exercise sets, and the agent's iteration cap cannot bound it — that counts model
 * round-trips, and one round-trip may ask for any number of moves at once. So the count has to
 * live where the spending happens.
 *
 * <p>Both failure directions are silent. Too permissive and a misread burns the budget before
 * anyone reads the transcript; too strict and the exercise looks unwinnable with nothing saying
 * why. Neither surfaces as an exception in a live run.
 *
 * <p>Fixtures are invented — the addresses and the key belong to no lesson.
 */
class MoveToolTest {

    private static final String TASK = "x-task";
    private static final List<String> POSITIONS = List.of("a1", "a2", "b1");
    private static final String KEY = "place";

    /** What the fake hub was handed, in order. */
    private final List<Object> sent = new ArrayList<>();

    @Test
    void stopsSendingOnceTheBudgetIsSpent(@TempDir Path root) {
        Tool<MoveTool.Move> tool = moveTool(root, 2);

        tool.handler().apply(new MoveTool.Move("a1"));
        tool.handler().apply(new MoveTool.Move("a2"));
        ToolOutput refused = tool.handler().apply(new MoveTool.Move("b1"));

        assertEquals(2, sent.size(), "the third move must not reach the hub");
        assertTrue(
                String.valueOf(refused.result()).contains("2"),
                () -> "the refusal must name the budget: " + refused.result());
    }

    /**
     * Returned rather than thrown. A spent budget is a fact the model should read and act on —
     * by reporting what it has, or stopping — where a throw becomes "Tool failed:" and reads to
     * the model like something worth retrying.
     */
    @Test
    void reportsTheRefusalToTheModelRatherThanThrowing(@TempDir Path root) {
        Tool<MoveTool.Move> tool = moveTool(root, 0);

        assertEquals(0, sent.size());
        assertTrue(String.valueOf(tool.handler().apply(new MoveTool.Move("a1")).result()).contains("0"));
    }

    @Test
    void keepsEveryResponseTheHubReturned(@TempDir Path root) {
        MoveTool moves = new MoveTool(hub(root), new MoveTool.Spec(KEY, POSITIONS, 9));
        Tool<MoveTool.Move> tool = moves.tool("move", "moves one");

        tool.handler().apply(new MoveTool.Move("a1"));
        tool.handler().apply(new MoveTool.Move("a2"));

        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), moves.responses(),
                "a reported result is checked against these, so order and completeness matter");
    }

    /**
     * The narrowing. Without it the model may name an address that does not exist, and the
     * exercise charges a call to say so — with it the provider cannot emit one, and the only
     * mistakes left are wrong addresses rather than impossible ones.
     */
    @Test
    void offersOnlyTheAddressesTheExerciseDefines(@TempDir Path root) {
        List<String> allowed = new ArrayList<>();
        moveTool(root, 9).spec().function().parameters()
                .at("/properties/target/enum")
                .forEach(node -> allowed.add(node.stringValue()));

        assertEquals(POSITIONS, allowed);
    }

    /** The key the hub wants comes from the bundle; nothing here knows what it is called. */
    @Test
    void sendsTheAddressUnderTheKeyTheExerciseNames(@TempDir Path root) {
        moveTool(root, 9).handler().apply(new MoveTool.Move("a1"));

        assertEquals(Map.of(KEY, "a1"), sent.getFirst());
    }

    private Tool<MoveTool.Move> moveTool(Path root, int maxMoves) {
        return new MoveTool(hub(root), new MoveTool.Spec(KEY, POSITIONS, maxMoves))
                .tool("move", "moves one");
    }

    /** Answers every attempt, counting them — retries are {@code ResilientHubTest}'s subject. */
    private ResilientHub hub(Path root) {
        HubSend sender = (label, taskName, answer) -> {
            sent.add(answer);
            return new HubResponse(200, TestHeaders.of(Map.of()), "{\"n\":" + sent.size() + "}");
        };

        return new ResilientHub(
                sender,
                TASK,
                RetryPolicy.defaults(),
                new RateLimitHeaders(List.of(), Duration.ofSeconds(60)),
                wait -> {},
                RunTranscript.open(root.resolve("logs"), "x01", Map.of(), List.of()));
    }
}
