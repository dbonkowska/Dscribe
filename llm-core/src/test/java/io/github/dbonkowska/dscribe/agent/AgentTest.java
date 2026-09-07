package io.github.dbonkowska.dscribe.agent;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.conversation.ToolCall;
import io.github.dbonkowska.dscribe.llm.ChatResponse;
import io.github.dbonkowska.dscribe.llm.ChatTransport;
import io.github.dbonkowska.dscribe.llm.ToolSpec;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.Toolbox;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop is the piece with no safe failure mode: it spends money per iteration, and every way
 * it can go wrong is quiet. An assistant turn appended without its tool calls, a tool result
 * that loses its id, an answer call dispatched as if it were an ordinary tool — each of those
 * reads to the model as "nothing happened", and the run burns its cap repeating itself.
 *
 * <p>Driven by a scripted transport rather than HTTP: the fake returns a queued choice per call
 * and keeps every request it was handed, so what the model *would have seen* is assertable.
 *
 * <p>Fixtures are invented — {@code lookup} and a verdict belong to no lesson.
 */
class AgentTest {

    record Lookup(String query) {}

    record Answer(String verdict) {}

    private static final AnswerTool<Answer> ANSWER =
            new AnswerTool<>("answer", "reports the final verdict", Answer.class);

    private static final List<Message> SEED = List.of(new Message(Role.user, "go"));

    private static final String ANSWER_ARGUMENTS = "{\"verdict\":\"guilty\"}";

    /** Names of the arguments each dispatched call arrived with, in dispatch order. */
    private final List<String> dispatched = new ArrayList<>();

    @Test
    void returnsTheAnswerToolsArgumentsDeserialised() {
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(call("call_1", "answer", ANSWER_ARGUMENTS)));

        Answer answer = agent(transport, 12).run(SEED, ANSWER);

        assertEquals(new Answer("guilty"), answer);
        assertEquals(1, transport.calls());
        assertEquals(List.of(), dispatched, "the answer tool is never dispatched");
    }

    @Test
    void feedsAToolResultBackAgainstTheCallItAnswers() {
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(call("call_1", "lookup", "{\"query\":\"x\"}")),
                toolCalls(call("call_2", "answer", ANSWER_ARGUMENTS)));

        agent(transport, 12).run(SEED, ANSWER);

        Message result = transport.request(1).getLast();
        assertEquals(Role.tool, result.role());
        assertEquals("call_1", result.toolCallId());
        assertEquals("found x", result.text());
    }

    @Test
    void dispatchesEveryCallOfOneTurnAsASingleIteration() {
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(
                        call("call_1", "lookup", "{\"query\":\"a\"}"),
                        call("call_2", "lookup", "{\"query\":\"b\"}"),
                        call("call_3", "lookup", "{\"query\":\"c\"}")),
                toolCalls(call("call_4", "answer", ANSWER_ARGUMENTS)));

        agent(transport, 12).run(SEED, ANSWER);

        assertEquals(List.of("a", "b", "c"), dispatched);
        assertEquals(2, transport.calls(), "the cap counts round-trips, not tool calls");
    }

    @Test
    void letsTheAnswerWinOverRealToolsInTheSameTurn() {
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(
                        call("call_1", "lookup", "{\"query\":\"x\"}"),
                        call("call_2", "answer", ANSWER_ARGUMENTS)));

        assertEquals(new Answer("guilty"), agent(transport, 12).run(SEED, ANSWER));
        assertEquals(List.of(), dispatched, "the answer ends the run wherever it appears in the turn");
    }

    @Test
    void givesUpAtTheCapCarryingTheTranscript() {
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(call("call_1", "lookup", "{\"query\":\"a\"}")),
                toolCalls(call("call_2", "lookup", "{\"query\":\"b\"}")),
                toolCalls(call("call_3", "lookup", "{\"query\":\"c\"}")));

        AgentLimitException thrown =
                assertThrows(AgentLimitException.class, () -> agent(transport, 3).run(SEED, ANSWER));

        assertEquals(3, thrown.iterations());
        assertEquals(SEED.getFirst(), thrown.transcript().getFirst(), "the seed opens the transcript");
        assertEquals(
                3,
                thrown.transcript().stream().filter(message -> message.role() == Role.assistant).count(),
                () -> "expected all three assistant turns in: " + thrown.transcript());
    }

    @Test
    void carriesOnAfterAToolFailure() {
        Tool<Lookup> exploding = new Tool<>("lookup", "finds things", Lookup.class, args -> {
            throw new RuntimeException("boom");
        });
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(call("call_1", "lookup", "{\"query\":\"x\"}")),
                toolCalls(call("call_2", "answer", ANSWER_ARGUMENTS)));

        Answer answer = new Agent(transport, new Toolbox(List.of(exploding)), 12).run(SEED, ANSWER);

        assertEquals(new Answer("guilty"), answer);
        String reported = transport.request(1).getLast().text();
        assertTrue(reported.startsWith("Tool failed: "), () -> "the model must see the failure: " + reported);
    }

    @Test
    void nudgesABareTextTurnBackTowardsTheAnswerTool() {
        ScriptedTransport transport = new ScriptedTransport(
                text("Let me think about this out loud."),
                toolCalls(call("call_1", "answer", ANSWER_ARGUMENTS)));

        assertEquals(new Answer("guilty"), agent(transport, 12).run(SEED, ANSWER));

        Message nudge = transport.request(1).getLast();
        assertEquals(Role.user, nudge.role());
        assertTrue(nudge.text().contains("answer"), () -> "the nudge must name the tool: " + nudge.text());
    }

    @Test
    void collapsesAResultOntoOneLine() {
        assertEquals("{\"a\": 1}", Agent.oneLine("{\"a\":\n  1}\n"));
    }

    @Test
    void rendersAnAbsentContentAsNothing() {
        // an assistant turn carrying tool calls has no content; logging it must not end the run
        assertEquals("", Agent.oneLine(null));
    }

    @Test
    void logsALongResultWhole() {
        // the result is the evidence: a truncated tool result is the one thing that cannot be
        // recovered after a run, and a wrong answer is unfalsifiable without it
        String whole = "x".repeat(4000);

        assertEquals(whole, Agent.oneLine(whole));
    }

    @Test
    void refusesAnAnswerToolNamedAfterARegisteredTool() {
        // both names come from the same properties file; a collision would otherwise reach the
        // request as two functions of one name, and the answer branch would win every call
        AnswerTool<Answer> clashing =
                new AnswerTool<>("lookup", "reports the final verdict", Answer.class);
        ScriptedTransport transport = new ScriptedTransport();

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> agent(transport, 12).run(SEED, clashing));

        assertTrue(thrown.getMessage().contains("lookup"), thrown::getMessage);
        assertEquals(0, transport.calls(), "nothing is spent on a run that cannot answer");
    }

    @Test
    void sendsAMalformedAnswerBackToTheModelRatherThanThrowing() {
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(call("call_1", "answer", "{\"verdict\":")),
                toolCalls(call("call_2", "answer", ANSWER_ARGUMENTS)));

        assertEquals(new Answer("guilty"), agent(transport, 12).run(SEED, ANSWER));

        Message refusal = transport.request(1).getLast();
        assertEquals(Role.tool, refusal.role());
        assertEquals("call_1", refusal.toolCallId());
        assertTrue(refusal.text().contains("answer"), refusal::text);
    }

    @Test
    void answersEveryCallOfATurnWhoseAnswerWasMalformed() {
        // a good answer ends the run, so the rest of its turn needs no results. A refused one does
        // not — and a tool call left unanswered makes the next request invalid at the provider.
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(
                        call("call_1", "lookup", "{\"query\":\"x\"}"),
                        call("call_2", "answer", "not json")),
                toolCalls(call("call_3", "answer", ANSWER_ARGUMENTS)));

        assertEquals(new Answer("guilty"), agent(transport, 12).run(SEED, ANSWER));

        List<String> answered = transport.request(1).stream()
                .filter(message -> message.role() == Role.tool)
                .map(Message::toolCallId)
                .toList();
        assertEquals(List.of("call_1", "call_2"), answered);
        assertEquals(List.of("x"), dispatched, "the turn is dispatched, not skipped");
    }

    @Test
    void untilNoToolCallsEndsOnTheFirstTurnThatAsksForNothing() {
        ScriptedTransport transport = new ScriptedTransport(text("done"));

        List<Message> conversation = agent(transport, 12).run(SEED, StopCondition.untilNoToolCalls());

        assertEquals(SEED.getFirst(), conversation.getFirst(), "the seed opens the conversation");
        Message last = conversation.getLast();
        assertEquals(Role.assistant, last.role());
        assertEquals("done", last.text());
        assertEquals(1, transport.calls());
        assertEquals(List.of(), dispatched);
    }

    @Test
    void untilNoToolCallsDispatchesToolsUntilTheModelJustReplies() {
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(call("call_1", "lookup", "{\"query\":\"x\"}")),
                text("done"));

        List<Message> conversation = agent(transport, 12).run(SEED, StopCondition.untilNoToolCalls());

        assertEquals(List.of("x"), dispatched);
        assertEquals("done", conversation.getLast().text());
        assertEquals(2, transport.calls());
    }

    @Test
    void nudgesATurnTheConditionDidNotAccept() {
        // the loop takes any predicate, and one that does not stop on plain text leaves a turn
        // with nothing to dispatch. Without the nudge the next request is the one that just came
        // back, and the run spends its whole cap asking again.
        StopCondition untilDone = turn -> "done".equals(turn.text());
        ScriptedTransport transport = new ScriptedTransport(text("thinking"), text("done"));

        List<Message> conversation = agent(transport, 12).run(SEED, untilDone);

        Message nudge = transport.request(1).getLast();
        assertEquals(Role.user, nudge.role());
        assertEquals(2, transport.calls());
        assertEquals("done", conversation.getLast().text());
    }

    @Test
    void givesUpAtTheCapWhateverEndsTheRun() {
        ScriptedTransport transport = new ScriptedTransport(
                toolCalls(call("call_1", "lookup", "{\"query\":\"a\"}")),
                toolCalls(call("call_2", "lookup", "{\"query\":\"b\"}")),
                toolCalls(call("call_3", "lookup", "{\"query\":\"c\"}")));

        AgentLimitException thrown = assertThrows(
                AgentLimitException.class,
                () -> agent(transport, 3).run(SEED, StopCondition.untilNoToolCalls()));

        assertEquals(3, thrown.iterations());
        assertEquals(List.of("a", "b", "c"), dispatched);
    }

    private Agent agent(ChatTransport transport, int maxIterations) {
        Tool<Lookup> lookup = new Tool<>("lookup", "finds things", Lookup.class, args -> {
            dispatched.add(args.query());
            return "found " + args.query();
        });
        return new Agent(transport, new Toolbox(List.of(lookup)), maxIterations);
    }

    private static ChatResponse.Choice toolCalls(ToolCall... calls) {
        return new ChatResponse.Choice(
                new Message(Role.assistant, null, List.of(calls), null), "tool_calls");
    }

    private static ChatResponse.Choice text(String content) {
        return new ChatResponse.Choice(new Message(Role.assistant, content), "stop");
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, "function", new ToolCall.Invocation(name, arguments));
    }

    /** Returns queued choices in order and keeps every request, so requests can be asserted on. */
    private static final class ScriptedTransport implements ChatTransport {

        private final Deque<ChatResponse.Choice> script;
        private final List<List<Message>> requests = new ArrayList<>();

        private ScriptedTransport(ChatResponse.Choice... choices) {
            script = new ArrayDeque<>(List.of(choices));
        }

        @Override
        public ChatResponse.Choice send(List<Message> messages, List<ToolSpec> tools, String toolChoice) {
            requests.add(List.copyOf(messages));
            if (script.isEmpty()) {
                throw new IllegalStateException("the agent asked for turn " + requests.size()
                        + "; the script has none left");
            }
            return script.removeFirst();
        }

        private int calls() {
            return requests.size();
        }

        /** The messages the agent had assembled by its {@code index}-th round-trip, zero-based. */
        private List<Message> request(int index) {
            return requests.get(index);
        }
    }
}
