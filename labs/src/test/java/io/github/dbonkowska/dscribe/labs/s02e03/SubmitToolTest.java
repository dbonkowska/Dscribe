package io.github.dbonkowska.dscribe.labs.s02e03;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.HubSend;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.tokens.TokenBudget;
import io.github.dbonkowska.dscribe.labs.tokens.Tokens;
import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard in front of an authority that charges to answer. What it protects is the attempt: an
 * over-long selection sent to the hub is rejected for its size, which says nothing about whether
 * the right events were in it, and the round is spent learning what could have been measured here.
 *
 * <p>Every "nothing was sent" assertion sits after the call it is about. Above it, it would test
 * the fixture — the list is empty before anything has run, whatever the code does.
 *
 * <p>Lines, severities, the format and the key are invented and belong to no lesson.
 */
class SubmitToolTest {

    private static final String TASK = "x-task";
    private static final String KEY = "text";
    private static final String FORMAT = "%s %s %s %s";

    private static final EventMap MAP = EventMap.parse(String.join("\n",
            "2030-01-01 10:00 HIGH pump stalled",
            "2030-01-01 10:05 LOW fan ok",
            "2030-01-01 10:20 HIGH pump stalled"),
            Pattern.compile("(?<date>\\S+) (?<time>\\S+) (?<severity>\\w+) (?<message>.*)"));

    /** What the fake hub was handed, in order. */
    private final List<Object> sent = new ArrayList<>();

    @Test
    void refusesASelectionOverTheBudgetWithoutSendingIt(@TempDir Path root) {
        int measured = Tokens.count(MAP.renderSubmission(List.of("E1", "E2"), FORMAT));
        Tool<SubmitTool.Selection> tool = submitTool(root, new TokenBudget(measured, 1)).tool("send", "sends");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new SubmitTool.Selection(List.of("E1", "E2"))));

        assertEquals(List.of(), sent, "an over-budget selection must not reach the hub");
        assertTrue(thrown.getMessage().contains(String.valueOf(measured))
                        && thrown.getMessage().contains(String.valueOf(measured - 1)),
                () -> "the model has to be told what it measured and what fits: " + thrown.getMessage());
    }

    /** The schema narrows ids, but a provider is not obliged to honour a schema. */
    @Test
    void refusesAnIdTheMapDoesNotHaveWithoutSendingAnything(@TempDir Path root) {
        Tool<SubmitTool.Selection> tool = submitTool(root, generous()).tool("send", "sends");

        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new SubmitTool.Selection(List.of("E1", "E9"))));

        assertEquals(List.of(), sent);
    }

    /** An empty selection is an empty submission, rejected for being empty — after it was paid for. */
    @Test
    void refusesAnEmptySelectionWithoutSendingAnything(@TempDir Path root) {
        Tool<SubmitTool.Selection> tool = submitTool(root, generous()).tool("send", "sends");

        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new SubmitTool.Selection(List.of())));

        assertEquals(List.of(), sent);
    }

    /** A model restating its selection may repeat an id; the submission must not repeat the line. */
    @Test
    void sendsARepeatedIdOnceUnderTheKeyTheExerciseNames(@TempDir Path root) {
        submitTool(root, generous()).tool("send", "sends")
                .handler().apply(new SubmitTool.Selection(List.of("E1", "E1", "E2")));

        assertEquals(List.of(Map.of(KEY, MAP.renderSubmission(List.of("E1", "E2"), FORMAT))), sent);
    }

    /**
     * The size comes back with every attempt, so the model can see how much room it has before
     * choosing what to add — and it is the size of what was sent, not of what was asked for.
     */
    @Test
    void returnsTheMeasuredSizeTheLimitAndTheHubsReplyVerbatim(@TempDir Path root) {
        Object result = submitTool(root, new TokenBudget(1_000, 100)).tool("send", "sends")
                .handler().apply(new SubmitTool.Selection(List.of("E2", "E1"))).result();

        String logs = MAP.renderSubmission(List.of("E1", "E2"), FORMAT);
        assertEquals(new SubmitTool.Attempt(Tokens.count(logs), 900, "{\"n\":1}"), result);
    }

    /**
     * The runner's first attempt goes through {@code submit} directly, the model's through the
     * handler. A reported flag is checked against these, so both have to be kept, in order.
     */
    @Test
    void keepsEveryResponseWhicheverWayTheAttemptWasMade(@TempDir Path root) {
        SubmitTool submit = submitTool(root, generous());

        submit.submit(List.of("E1"));
        submit.tool("send", "sends").handler().apply(new SubmitTool.Selection(List.of("E2")));

        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), submit.responses());
    }

    /**
     * The narrowing. Without it the model may name an entry that does not exist and spend an
     * iteration being told so — with it the provider cannot emit one.
     */
    @Test
    void offersOnlyTheIdsTheMapHas(@TempDir Path root) {
        List<String> allowed = new ArrayList<>();
        submitTool(root, generous()).tool("send", "sends").spec().function().parameters()
                .at("/properties/ids/items/enum")
                .forEach(node -> allowed.add(node.stringValue()));

        assertEquals(List.of("E1", "E2"), allowed);
    }

    private SubmitTool submitTool(Path root, TokenBudget budget) {
        return new SubmitTool(hub(root), MAP, new SubmitTool.Spec(KEY, FORMAT, budget));
    }

    /** A budget nothing here will bump into, for the cases that are not about size. */
    private static TokenBudget generous() {
        return new TokenBudget(1_000, 0);
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
