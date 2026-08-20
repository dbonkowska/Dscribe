package io.github.dbonkowska.dscribe.agent;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.conversation.ToolCall;
import io.github.dbonkowska.dscribe.llm.ChatTransport;
import io.github.dbonkowska.dscribe.llm.ToolSpec;
import io.github.dbonkowska.dscribe.tool.Toolbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets a model drive a multi-step process: it picks a tool, reads the result, and picks again,
 * until it calls the answer tool.
 *
 * <p>The cap counts model round-trips rather than tool calls — a turn with five parallel calls
 * is one iteration. Nothing else ends the loop: a tool failure goes back to the model as text
 * and costs an iteration, and so does a nudge after a turn that produced no calls at all.
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
        List<Message> messages = new ArrayList<>(seed);

        List<ToolSpec> specs = new ArrayList<>(tools.specs());
        specs.add(answer.spec());

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
            for (ToolCall call : turn.toolCalls()) {
                if (answer.name().equals(call.function().name())) {
                    // logged like any other call: a run that answers on turn 1, having consulted
                    // nothing, otherwise leaves no trace of having done so
                    log.info("{}. {} {}", iteration, call.function().name(), call.function().arguments());
                    return MAPPER.readValue(call.function().arguments(), answer.type());
                }
            }

            for (ToolCall call : turn.toolCalls()) {
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