package io.github.dbonkowska.dscribe.labs.s03e02;

import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final int THRESHOLD = 3;
    private static final int MAX_REPLY = 200;
    private static final Guard.Learning LEARNING = new Guard.Learning(".ign", "path", "data");

    /** One post the fake received. */
    private record Posted(String label, String path, JsonNode body) {}

    private final List<Posted> posted = new ArrayList<>();
    private final List<Duration> slept = new ArrayList<>();

    /** Replies come off this queue, one per post; an empty queue answers with a marker. */
    private final List<String> queue = new ArrayList<>();

    /** Blank unless a test says where a refusal names its culprit. */
    private String culpritPointer = "";

    private ShellTool shell = shell(List.of());

    private ShellTool shell(List<String> forbidden) {
        return shell(forbidden, List.of(), RetryPolicy.defaults());
    }

    private ShellTool shell(List<String> forbidden, List<String> transientCodes, RetryPolicy policy) {
        return shell(forbidden, transientCodes, List.of(), policy);
    }

    private ShellTool shell(
            List<String> forbidden, List<String> transientCodes, List<String> causedCodes, RetryPolicy policy) {
        Iterator<String> replies = queue.iterator();
        return new ShellTool(
                (label, path, body) -> {
                    posted.add(new Posted(label, path, MAPPER.valueToTree(body)));
                    return replies.hasNext() ? replies.next() : "{\"out\":\"a\"}";
                },
                new ShellTool.Spec(PATH, KEY, forbidden, transientCodes, causedCodes, THRESHOLD, MAX_REPLY, LEARNING,
                        culpritPointer),
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

    private static final String LOCKED = "{\"code\":\"LOCKED\"}";

    private Tool<ShellTool.Command> causedTool() {
        shell = shell(List.of(), List.of(), List.of("LOCKED"), POLICY);
        return tool();
    }

    /**
     * Sending the same command again would meet the same refusal, so this waits once and does not
     * resend. The note names the command the reply itself names, which is not necessarily one this
     * tool sent last.
     */
    @Test
    void waitsOutACausedRefusalWithoutRetryingAndNamesTheCommandTheReplyNames() {
        culpritPointer = "/ban/command";
        String banned = "{\"code\":\"LOCKED\",\"ban\":{\"command\":\"cmd-x\"}}";
        queue.addAll(List.of("{\"out\":\"ok\"}", banned));
        Tool<ShellTool.Command> tool = causedTool();
        tool.handler().apply(new ShellTool.Command("cmd-a"));

        String result = (String) tool.handler().apply(new ShellTool.Command("cmd-b")).result();

        assertEquals(2, posted.size(), "a caused refusal is not retried");
        assertEquals(List.of(Duration.ofSeconds(1)), slept);
        String firstLine = result.lines().findFirst().orElseThrow();
        assertTrue(firstLine.contains("LOCKED"), firstLine);
        assertTrue(firstLine.contains("not retried"), firstLine);
        assertTrue(firstLine.contains("caused by: cmd-x"), "it names what the reply names: " + firstLine);
        assertFalse(firstLine.contains("cmd-a"), "the command before is not a suspect: " + firstLine);
        assertTrue(result.endsWith(banned), "the raw reply stays beneath the note: " + result);
        assertEquals(List.of("{\"out\":\"ok\"}", banned), shell.replies(), "the kept replies carry no note");
    }

    /**
     * Without a name in the reply the tool knows only which command it was answering, and says that:
     * it does not claim that command, or any other, was the cause.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"code\":\"LOCKED\"}", "{\"code\":\"LOCKED\",\"ban\":{}}", "LOCKED, not json"})
    void saysWhichCommandWasRefusedWhenTheReplyNamesNoCulprit(String reply) {
        culpritPointer = "/ban/command";
        queue.addAll(List.of("{\"out\":\"ok\"}", reply));
        Tool<ShellTool.Command> tool = causedTool();
        tool.handler().apply(new ShellTool.Command("cmd-a"));

        String result = (String) tool.handler().apply(new ShellTool.Command("cmd-b")).result();

        String firstLine = result.lines().findFirst().orElseThrow();
        assertTrue(firstLine.contains("not retried"), firstLine);
        assertTrue(firstLine.contains("refused: cmd-b"), firstLine);
        assertFalse(firstLine.contains("caused by"), "nothing is claimed about the cause: " + firstLine);
    }

    @Test
    void asksNothingOfTheReplyWhenNoPointerIsConfigured() {
        queue.add("{\"code\":\"LOCKED\",\"ban\":{\"command\":\"cmd-x\"}}");

        String firstLine = ((String) causedTool().handler().apply(new ShellTool.Command("cmd-a")).result())
                .lines().findFirst().orElseThrow();

        assertTrue(firstLine.contains("refused: cmd-a"), firstLine);
        assertFalse(firstLine.contains("cmd-x"), "an unconfigured lookup reads nothing: " + firstLine);
    }

    /**
     * A busy reply is retried, and the retry can land on a ban: the sequence the classification exists
     * for. The refusal is classified on every reply received, not only the first.
     */
    @Test
    void classifiesACausedRefusalThatArrivesOnARetry() {
        queue.addAll(List.of(BUSY, LOCKED));
        shell = shell(List.of(), List.of("BUSY"), List.of("LOCKED"), POLICY);

        String result = (String) tool().handler().apply(new ShellTool.Command("cmd-a")).result();

        assertEquals(2, posted.size(), "the caused refusal is not retried");
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1)), slept);
        String firstLine = result.lines().findFirst().orElseThrow();
        assertTrue(firstLine.contains("caused"), firstLine);
        assertTrue(firstLine.contains("LOCKED"), firstLine);
        assertTrue(firstLine.contains("not retried"), firstLine);
        assertTrue(firstLine.contains("2s"), "the wait includes the one already spent: " + firstLine);
        assertTrue(result.endsWith(LOCKED), "the raw reply stays beneath the note: " + result);
        assertEquals(List.of(BUSY, LOCKED), shell.replies(), "the kept replies carry no note");
    }

    @Test
    void passesAnUnconfiguredCodeThroughVerbatim() {
        queue.add("{\"code\":\"OTHER\"}");

        Object result = causedTool().handler().apply(new ShellTool.Command("cmd-a")).result();

        assertEquals("{\"code\":\"OTHER\"}", result);
        assertTrue(slept.isEmpty());
    }

    private void send(Tool<ShellTool.Command> tool, String... commands) {
        for (String command : commands) {
            tool.handler().apply(new ShellTool.Command(command));
        }
    }

    /**
     * The count in the message is what was really sent. A model told "you repeated this 5 times" after
     * three sends learns the wrong thing about how much it has already spent.
     */
    @Test
    void refusesTheSameCommandOnceItHasBeenSentThresholdTimesInARow() {
        Tool<ShellTool.Command> tool = tool();
        send(tool, "ls a", "ls a", "ls a");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ShellTool.Command("ls a")));

        assertTrue(thrown.getMessage().contains("3 times"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("Nothing was sent"), thrown::getMessage);
        assertEquals(3, posted.size(), "the refused repeat must not reach the hub");
    }

    /** Spelling is not a different attempt: two whitespace variants of one command are one command. */
    @Test
    void countsWhitespaceVariantsOfACommandAsTheSameCommand() {
        Tool<ShellTool.Command> tool = tool();
        send(tool, "ls  a", "ls a", " ls a");

        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ShellTool.Command("ls   a")));

        assertEquals(3, posted.size());
    }

    /** Progress is a different command: what is counted is a run of one, not a total. */
    @Test
    void anotherCommandInBetweenResetsTheCount() {
        send(tool(), "a", "a", "b", "a", "a", "a");

        assertEquals(6, posted.size());
    }

    /**
     * A command the guard turned away was never sent, so it neither counts as a repeat nor breaks a
     * run of them, and a refused repeat does not push the count past what was really sent.
     */
    @Test
    void countsOnlyWhatWasActuallySent() {
        shell = shell(List.of("zone"));
        Tool<ShellTool.Command> tool = tool();
        send(tool, "ls a", "ls a");
        assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ShellTool.Command("ls zone")));
        send(tool, "ls a");

        for (int i = 0; i < 2; i++) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> tool.handler().apply(new ShellTool.Command("ls a")));
            assertTrue(thrown.getMessage().contains("3 times"), thrown::getMessage);
        }

        assertEquals(3, posted.size());
    }

    /**
     * The same command is progress when the environment answers differently: climbing directories
     * with one command, or polling something that changes. Only a command that gets the same answer
     * back is going nowhere.
     */
    @Test
    void doesNotCountARepeatWhoseReplyChanged() {
        queue.addAll(List.of("r1", "r2", "r3", "r4", "r5", "r6"));
        shell = shell(List.of());
        Tool<ShellTool.Command> tool = tool();

        send(tool, "up", "up", "up", "up", "up", "up");

        assertEquals(6, posted.size());
    }

    /** A changed reply restarts the run rather than excusing it: three identical answers still end it. */
    @Test
    void restartsTheCountWhenTheReplyChangesThenRefusesAtTheThreshold() {
        queue.addAll(List.of("r1", "r1", "r2", "r2", "r2"));
        shell = shell(List.of());
        Tool<ShellTool.Command> tool = tool();
        send(tool, "up", "up", "up", "up", "up");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ShellTool.Command("up")));

        assertTrue(thrown.getMessage().contains("3 times"), thrown::getMessage);
        assertEquals(5, posted.size());
    }

    private static String listing(String path, String entry) {
        return MAPPER.writeValueAsString(java.util.Map.of("code", 150, "path", path, "data", entry));
    }

    /**
     * The model may write the command that reads a listing and the command that touches what it
     * lists in one turn, before it could have seen the listing. They run in the order written, so by
     * the time the second is checked the first has been answered.
     */
    @Test
    void refusesWhatAListingItReadNamesEvenWhenBothCommandsWereWrittenTogether() {
        queue.add(listing("/d/.ign", "x.txt"));
        shell = shell(List.of());
        Tool<ShellTool.Command> tool = tool();
        tool.handler().apply(new ShellTool.Command("cat /d/.ign"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ShellTool.Command("cat /d/x.txt")));

        assertTrue(thrown.getMessage().contains("/d/x.txt"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("Nothing was sent"), thrown::getMessage);
        assertEquals(1, posted.size(), "the refused command must not reach the hub");
    }

    @Test
    void stillSendsWhatTheListingDoesNotName() {
        queue.add(listing("/d/.ign", "x.txt"));
        shell = shell(List.of());
        Tool<ShellTool.Command> tool = tool();

        send(tool, "cat /d/.ign", "cat /d/y.txt");

        assertEquals(2, posted.size());
    }

    /**
     * A file can come back as megabytes. Handed to the model whole it would overflow the context and
     * end the run, so what the model reads is capped — but the start and the end are kept, since a
     * program's output tends to end with what it produced. What was cut is said, with its size.
     */
    @Test
    void keepsTheStartAndEndOfAReplyTooLongToHandOver() {
        String huge = "head-" + "x".repeat(5000) + "-tail";
        queue.add(huge);
        shell = shell(List.of());

        String result = (String) tool().handler().apply(new ShellTool.Command("cat big")).result();

        assertTrue(result.startsWith("head-"), result);
        assertTrue(result.endsWith("-tail"), result);
        assertTrue(result.length() < 2 * MAX_REPLY, "what the model reads has to stay small: " + result.length());
        assertTrue(result.contains("5010"), "it has to say how long the reply really was: " + result);
        assertEquals(List.of(huge), shell.replies(), "the kept reply is whole");
    }

    @Test
    void handsOverAReplyExactlyAtTheCapUntouched() {
        String exact = "y".repeat(MAX_REPLY);
        queue.add(exact);
        shell = shell(List.of());

        Object result = tool().handler().apply(new ShellTool.Command("cat exact")).result();

        assertEquals(exact, result);
    }

    /** The note that says a refusal was waited on must survive the cut: it is the part that is about the model. */
    @Test
    void keepsARefusalNoteWhenTheReplyBeneathItIsCut() {
        String big = BUSY + "z".repeat(5000);
        queue.addAll(List.of(big, big, big, big));

        String result = (String) transientTool().handler().apply(new ShellTool.Command("ls")).result();

        assertTrue(result.lines().findFirst().orElseThrow().contains("transient refusal BUSY"), result);
        assertTrue(result.length() < 2 * MAX_REPLY + 200, "" + result.length());
    }
}
