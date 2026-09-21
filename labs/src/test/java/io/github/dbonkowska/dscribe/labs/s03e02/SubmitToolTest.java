package io.github.dbonkowska.dscribe.labs.s03e02;

import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool that hands the run's result to the hub, and takes nothing from the model to do it.
 *
 * <p>The code is read out of what the shell replied, by the runner, and never typed by the model: a
 * model that writes a code it merely saw a moment ago will sometimes write a slightly different one,
 * and the refusal that follows reads as a wrong answer rather than a copying slip. Every "nothing was
 * sent" assertion sits after the call it is about. The pattern and the replies are invented.
 */
class SubmitToolTest {

    private static final Pattern CODE = Pattern.compile("[a-f0-9]{8}");

    /** What the shell tool has replied so far, which the test grows as a run would. */
    private final List<String> replies = new ArrayList<>();

    /** One send the fake received. */
    private record Sent(String label, String answer) {}

    private final List<Sent> sent = new ArrayList<>();

    private final SubmitTool submit = new SubmitTool(
            () -> replies,
            CODE,
            (label, answer) -> {
                sent.add(new Sent(label, answer));
                return "{\"reply\":" + sent.size() + "}";
            });

    private Tool<SubmitTool.Submit> tool() {
        return submit.tool("send", "sends");
    }

    /** With nothing to type, there is nothing for the model to get slightly wrong. */
    @Test
    void takesNothingFromTheModel() {
        var properties = tool().spec().function().parameters().at("/properties");

        assertTrue(properties.isObject(), "the schema has to declare its properties, empty");
        assertEquals(0, properties.size());
    }

    @Test
    void refusesToSubmitBeforeAnyReplyHasProducedACode() {
        replies.add("x");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool().handler().apply(new SubmitTool.Submit()));

        assertTrue(thrown.getMessage().contains("Nothing was sent"), thrown::getMessage);
        assertEquals(0, sent.size(), "no code, so no request");
    }

    @Test
    void sendsTheCodeTheRepliesProducedAndReturnsTheHubsReplyVerbatim() {
        replies.addAll(List.of("x", "{\"out\":\"deadbeef\"}"));

        Object result = tool().handler().apply(new SubmitTool.Submit()).result();

        assertEquals(1, sent.size());
        assertEquals("deadbeef", sent.getFirst().answer(), "exactly the code, not the reply around it");
        assertEquals("{\"reply\":1}", result);
    }

    /** The environment may print more than one; the latest is the one the run just earned. */
    @Test
    void sendsTheMostRecentMatchingReply() {
        replies.addAll(List.of("deadbeef", "cafef00d"));

        tool().handler().apply(new SubmitTool.Submit());

        assertEquals("cafef00d", sent.getFirst().answer());
    }

    @Test
    void keepsTheHubsRepliesAndNumbersEachSubmission() {
        replies.add("deadbeef");
        Tool<SubmitTool.Submit> tool = tool();

        tool.handler().apply(new SubmitTool.Submit());
        tool.handler().apply(new SubmitTool.Submit());

        assertEquals(List.of("{\"reply\":1}", "{\"reply\":2}"), submit.responses());
        assertEquals("submission 1", sent.get(0).label());
        assertEquals("submission 2", sent.get(1).label());
    }
}
