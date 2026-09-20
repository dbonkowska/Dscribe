package io.github.dbonkowska.dscribe.labs.s03e02;

import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one door the model has to a shell it cannot see into.
 *
 * <p>Every reply is kept raw, whatever the tool tells the model: the code the run submits is read
 * back out of those replies, so a note prepended to one must never be able to change what a later
 * step finds. Every "nothing was posted" assertion sits after the call it is about. Paths, keys and
 * commands are invented and belong to no lesson.
 */
class ShellToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = "/x/shell";
    private static final String KEY = "cmd";

    /** One post the fake received. */
    private record Posted(String label, String path, JsonNode body) {}

    private final List<Posted> posted = new ArrayList<>();
    private final List<Duration> slept = new ArrayList<>();

    /** Replies come off this queue, one per post; an empty queue answers with a marker. */
    private final List<String> queue = new ArrayList<>();

    private ShellTool shell = shell(List.of());

    private ShellTool shell(List<String> forbidden) {
        return shell(forbidden, List.of(), RetryPolicy.defaults());
    }

    private ShellTool shell(List<String> forbidden, List<String> transientCodes, RetryPolicy policy) {
        Iterator<String> replies = queue.iterator();
        return new ShellTool(
                (label, path, body) -> {
                    posted.add(new Posted(label, path, MAPPER.valueToTree(body)));
                    return replies.hasNext() ? replies.next() : "{\"out\":\"a\"}";
                },
                new ShellTool.Spec(PATH, KEY, forbidden, transientCodes, List.of()),
                policy,
                slept::add);
    }

    private Tool<ShellTool.Command> tool() {
        return shell.tool("run", "runs");
    }

    @Test
    void postsTheCommandUnderTheConfiguredKeyAndReturnsTheReplyVerbatim() {
        Object result = tool().handler().apply(new ShellTool.Command("ls /data")).result();

        assertEquals(1, posted.size());
        assertEquals(PATH, posted.getFirst().path());
        assertEquals(MAPPER.readTree("{\"cmd\":\"ls /data\"}"), posted.getFirst().body());
        assertEquals("{\"out\":\"a\"}", result, "the model reads what the shell said, word for word");
    }

    @Test
    void refusesABlankCommand() {
        assertThrows(IllegalArgumentException.class,
                () -> tool().handler().apply(new ShellTool.Command("   ")));

        assertEquals(0, posted.size(), "a blank command must not cost a request");
    }

    /** The submit tool reads its code from these, so they must be exactly what the hub said. */
    @Test
    void keepsEveryRawReplyInOrder() {
        queue.addAll(List.of("first", "second"));
        shell = shell(List.of());
        Tool<ShellTool.Command> tool = tool();

        tool.handler().apply(new ShellTool.Command("one"));
        tool.handler().apply(new ShellTool.Command("two"));

        assertEquals(List.of("first", "second"), shell.replies());
    }

    @Test
    void takesOnlyTheCommandFromTheModel() {
        JsonNode properties = tool().spec().function().parameters().at("/properties");

        assertEquals(List.of("command"), properties.propertyNames().stream().toList());
    }

    /**
     * A refused command costs nothing: the model is told which root it touched, and is told that
     * nothing was sent, so it does not wonder whether the command ran.
     */
    @Test
    void refusesACommandThatAddressesAForbiddenRootBeforeSendingIt() {
        shell = shell(List.of("zone"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool().handler().apply(new ShellTool.Command("cat ./zone/a")));

        assertTrue(thrown.getMessage().contains("zone"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("Nothing was sent"), thrown::getMessage);
        assertEquals(0, posted.size(), "the refusal has to come before the request");
    }

    @Test
    void sendsACommandThatOnlyContainsARootsName() {
        shell = shell(List.of("zone"));

        tool().handler().apply(new ShellTool.Command("cat ozone/a"));

        assertEquals(1, posted.size());
    }

    /** Four attempts, one second doubling, five seconds at most: waits of 1s, 2s and 4s. */
    private static final RetryPolicy POLICY =
            new RetryPolicy(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5));

    private static final String BUSY = "{\"code\":\"BUSY\"}";

    private Tool<ShellTool.Command> transientTool() {
        shell = shell(List.of(), List.of("BUSY"), POLICY);
        return tool();
    }

    /** The retries worked, so the model has nothing to be told: it reads the answer it asked for. */
    @Test
    void waitsOutATransientRefusalAndHandsBackTheReplyThatFollowed() {
        queue.addAll(List.of(BUSY, BUSY, "{\"out\":\"ok\"}"));

        Object result = transientTool().handler().apply(new ShellTool.Command("ls")).result();

        assertEquals(3, posted.size());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), slept);
        assertEquals("{\"out\":\"ok\"}", result, "a recovered command reads as an ordinary one");
    }

    /**
     * When the retries run out the model is told what happened and how long it cost, so it does not
     * mistake the refusal for an answer about its command. The kept reply stays raw.
     */
    @Test
    void namesAPersistentTransientRefusalAndWhatWaitingCost() {
        queue.addAll(List.of(BUSY, BUSY, BUSY, BUSY));

        String result = (String) transientTool().handler().apply(new ShellTool.Command("ls")).result();

        assertEquals(4, posted.size());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), slept);
        String firstLine = result.lines().findFirst().orElseThrow();
        assertTrue(firstLine.contains("transient"), firstLine);
        assertTrue(firstLine.contains("BUSY"), firstLine);
        assertTrue(firstLine.contains("7s"), firstLine);
        assertTrue(firstLine.contains("3 retries"), firstLine);
        assertTrue(result.endsWith(BUSY), "the last raw reply stays beneath the note: " + result);
        assertEquals(List.of(BUSY, BUSY, BUSY, BUSY), shell.replies(), "the kept replies carry no note");
    }

    /** Only a configured code is waited on. Anything else is an answer, and answers are not retried. */
    @Test
    void doesNotRetryAnOrdinaryFailure() {
        queue.add("{\"error\":\"no such file\"}");

        Object result = transientTool().handler().apply(new ShellTool.Command("cat x")).result();

        assertEquals(1, posted.size());
        assertTrue(slept.isEmpty());
        assertEquals("{\"error\":\"no such file\"}", result);
    }
}
