package io.github.dbonkowska.dscribe.labs.s02e04;

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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard in front of the one call that is judged. An answer missing a field, or naming one
 * twice, still reaches the hub as a well-formed request — and its rejection then reads as a wrong
 * value, which sends the model back to searching for something it had already found.
 *
 * <p>Every "nothing was sent" assertion sits after the call it is about. Field names and formats
 * are invented and belong to no lesson.
 */
class SubmitToolTest {

    private static final String TASK = "x-task";
    private static final List<TaskParams.Field> FIELDS = List.of(
            new TaskParams.Field("when", "[0-9]{4}"),
            new TaskParams.Field("word", "[a-z]+"));

    /** What the fake hub was handed, in order. */
    private final List<Object> sent = new ArrayList<>();

    /**
     * The narrowing. Without it the model may name a field the answer has no slot for and spend an
     * iteration being told so — with it the provider cannot emit one.
     */
    @Test
    void offersOnlyTheConfiguredFields(@TempDir Path root) {
        List<String> allowed = new ArrayList<>();
        submitTool(root).tool("send", "sends").spec().function().parameters()
                .at("/properties/values/items/properties/field/enum")
                .forEach(node -> allowed.add(node.stringValue()));

        assertEquals(List.of("when", "word"), allowed);
    }

    @Test
    void sendsEveryFieldUnderItsNameAndReturnsTheReplyVerbatim(@TempDir Path root) {
        Object result = submitTool(root).tool("send", "sends").handler()
                .apply(submission(value("when", "2030"), value("word", "abc")))
                .result();

        assertEquals(List.of(Map.of("when", "2030", "word", "abc")), sent);
        assertEquals("{\"n\":1}", result, "the hub's account of what is wrong is the only guidance the next search gets");
    }

    /** A field left out is not a field left empty: the hub would read it as absent, not as unknown. */
    @Test
    void refusesAMissingFieldWithoutSendingAnything(@TempDir Path root) {
        Tool<SubmitTool.Submission> tool = submitTool(root).tool("send", "sends");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(submission(value("when", "2030"))));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("word"), thrown::getMessage);
    }

    /** Two values for one slot: whichever were sent, the other was the model's answer too. */
    @Test
    void refusesAFieldGivenTwiceWithoutSendingAnything(@TempDir Path root) {
        Tool<SubmitTool.Submission> tool = submitTool(root).tool("send", "sends");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(
                        submission(value("when", "2030"), value("when", "2031"), value("word", "abc"))));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("when"), thrown::getMessage);
    }

    /** The schema narrows fields, but a provider is not obliged to honour a schema. */
    @Test
    void refusesAnUnknownFieldWithoutSendingAnything(@TempDir Path root) {
        Tool<SubmitTool.Submission> tool = submitTool(root).tool("send", "sends");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(
                        submission(value("when", "2030"), value("word", "abc"), value("other", "x"))));

        assertEquals(List.of(), sent);
        assertTrue(thrown.getMessage().contains("other"), thrown::getMessage);
    }

    /** A reported flag is checked against these, so order and completeness matter. */
    @Test
    void keepsEveryResponseTheHubReturned(@TempDir Path root) {
        SubmitTool submit = submitTool(root);
        Tool<SubmitTool.Submission> tool = submit.tool("send", "sends");

        tool.handler().apply(submission(value("when", "2030"), value("word", "abc")));
        tool.handler().apply(submission(value("when", "2031"), value("word", "xyz")));

        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), submit.responses());
    }

    private static SubmitTool.Value value(String field, String value) {
        return new SubmitTool.Value(field, value);
    }

    private static SubmitTool.Submission submission(SubmitTool.Value... values) {
        return new SubmitTool.Submission(List.of(values));
    }

    private SubmitTool submitTool(Path root) {
        return new SubmitTool(hub(root), new SubmitTool.Spec(FIELDS));
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
