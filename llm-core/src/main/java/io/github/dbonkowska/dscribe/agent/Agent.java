package io.github.dbonkowska.dscribe.agent;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.conversation.ToolCall;
import io.github.dbonkowska.dscribe.llm.ChatTransport;
import io.github.dbonkowska.dscribe.llm.ToolSpec;
import io.github.dbonkowska.dscribe.tool.Toolbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets a model drive a multi-step process: it picks a tool, reads the result, and picks again,
 * until it calls the answer tool.
 *
 * <p>The cap counts model round-trips rather than tool calls — a turn with five parallel calls
 * is one iteration. Nothing else ends the loop: a tool failure goes back to the model as text
 * and costs an iteration, so does a nudge after a turn that produced no calls at all, and so
 * does an answer whose arguments would not deserialise.
 *
 * <p>{@code Agent} knows nothing about any particular task. Every name the model sees arrives
 * from the caller, through the tools and the answer tool.
 */
public final class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ChatTransport llm;
    private final Toolbox tools;
    private final int maxIterations;

    public Agent(ChatTransport llm, Toolbox tools, int maxIterations) {
        this.llm = llm;
        this.tools = tools;
        this.maxIterations = maxIterations;
    }

    public <T> T run(List<Message> seed, AnswerTool<T> answer) {
        List<ToolSpec> specs = specs(answer);
        List<Message> messages = new ArrayList<>(seed);

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            Message turn = llm.send(messages, specs, "auto").message();
            messages.add(turn);

            if (!turn.hasToolCalls()) {
                // the nudge costs an iteration, so it has to be visible while it happens
                log.info("{}. no tool calls, nudging: {}", iteration, oneLine(turn.content()));
                messages.add(new Message(
                        Role.user, "You must finish by calling the " + answer.name() + " tool."));
                continue;
            }

            // scanned before anything is dispatched: the answer wins wherever it sits in the turn
            Map<String, Message> refused = new LinkedHashMap<>();
            for (ToolCall call : turn.toolCalls()) {
                if (!answer.name().equals(call.function().name())) {
                    continue;
                }
                // logged like any other call: a run that answers on turn 1, having consulted
                // nothing, otherwise leaves no trace of having done so
                log.info("{}. {} {}", iteration, call.function().name(), call.function().arguments());
                try {
                    return MAPPER.readValue(call.function().arguments(), answer.type());
                } catch (JacksonException e) {
                    // the answer's arguments are the one payload nothing validates on the way in.
                    // A model that malforms them is one turn from being right, so it hears about
                    // it, exactly as Toolbox tells it about any other bad call — a run that dies
                    // here has already been paid for.
                    log.info("   -> malformed answer: {}", oneLine(e.getMessage()));
                    refused.put(call.id(), Message.toolResult(call.id(),
                            "Could not read the arguments to " + answer.name() + ": " + e
                                    + ". Call it again with arguments matching its schema."));
                }
            }

            for (ToolCall call : turn.toolCalls()) {
                Message refusal = refused.get(call.id());
                if (refusal != null) {
                    // a refused answer still owes a result against its id, and the rest of the
                    // turn is dispatched rather than skipped: a tool call left unanswered makes
                    // the next request invalid at the provider
                    messages.add(refusal);
                    continue;
                }

                // logged before it runs, so a model spinning on identical calls is visible live
                log.info("{}. {} {}", iteration, call.function().name(), call.function().arguments());

                Message result = tools.invoke(call);

                // and the result too: without it, a wrong answer gives no way to tell whether the
                // model reasoned badly or was handed something other than what it expected
                log.info("   -> {}", oneLine(result.content()));

                messages.add(result);
            }
        }

        throw new AgentLimitException(maxIterations, messages);
    }

    /**
     * What the model is offered: the toolbox's tools, and the answer tool after them.
     *
     * <p>A name shared with a registered tool has to be rejected here, because nothing downstream
     * can see it. {@link Toolbox} dedupes its own, but the answer tool never goes through it — so
     * two functions of one name reach the request, and the scan above wins for every call. The
     * real tool becomes unreachable and the run ends on its first turn with whatever arguments the
     * model meant for it, failing as a wrong answer rather than as an error. Both names come from
     * the same properties file, which is exactly where a typo lands.
     */
    private List<ToolSpec> specs(AnswerTool<?> answer) {
        List<ToolSpec> specs = new ArrayList<>(tools.specs());
        for (ToolSpec spec : specs) {
            if (spec.function().name().equals(answer.name())) {
                throw new IllegalArgumentException(
                        "The answer tool's name is already a registered tool: " + answer.name());
            }
        }
        specs.add(answer.spec());
        return specs;
    }

    /**
     * A result or a turn on one line, whole. Nothing is truncated: the tool result is the only
     * record of what the model was actually working from, and it cannot be recovered after a run.
     *
     * <p>Null-tolerant — an assistant turn carrying tool calls has no content, and a logging
     * helper that throws would take down the run it was added to explain.
     */
    static String oneLine(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").trim();
    }
}
