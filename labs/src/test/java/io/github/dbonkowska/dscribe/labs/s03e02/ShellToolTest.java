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
        Iterator<String> replies = queue.iterator();
        return new ShellTool(
                (label, path, body) -> {
                    posted.add(new Posted(label, path, MAPPER.valueToTree(body)));
                    return replies.hasNext() ? replies.next() : "{\"out\":\"a\"}";
                },
                new ShellTool.Spec(PATH, KEY, forbidden, List.of(), List.of()),
                RetryPolicy.defaults(),
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
}
