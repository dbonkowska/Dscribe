package io.github.dbonkowska.dscribe.labs.s02e05;

import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sends a sequence, after making sure it is one.
 *
 * <p>Thin on purpose. Every judgement about a sequence belongs to {@link Commands}, which is pure
 * and can be asserted by equality; what is left here is sending what passed and handing back what
 * came back. The split is what makes a rejection in the transcript unambiguous: the hub was reached
 * only by sequences this code had nothing left to say about.
 *
 * <p>Nothing is shared with s02e04's tool of the same name. A type imported from a finished lesson
 * would freeze it.
 */
final class SubmitTool {

    private static final Logger log = LoggerFactory.getLogger(SubmitTool.class);

    /** The whole schema the model sees: the commands to run, in the order they should run. */
    record Submission(List<String> instructions) {}

    /**
     * One call to the hub, returning the body as it came — {@code ResilientHub::call}, and a
     * recording lambda in tests.
     */
    @FunctionalInterface
    interface Send {
        String send(String label, Object answer);
    }

    private final Send send;
    private final Commands commands;

    /**
     * Every reply the hub returned, in order.
     *
     * <p>The result is in what the hub said, not in what the model reports about it — so the runner
     * reads it from here rather than asking the model to repeat it back. A plausible answer that no
     * reply ever contained cannot end the run.
     */
    private final List<String> responses = new ArrayList<>();

    private int attempts;

    SubmitTool(Send send, Commands commands) {
        this.send = send;
        this.commands = commands;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Submission> tool(String name, String description) {
        return new Tool<>(name, description, Submission.class,
                args -> ToolOutput.of(submit(args.instructions())));
    }

    /**
     * Each refusal throws. {@code Toolbox} turns that into a tool result the model reads, and every
     * one of them is something to correct and send again.
     *
     * <p>A provider that ignores the schema can leave the list out entirely; {@code check} refuses
     * that alongside an empty one, so nothing here re-states it.
     */
    private String submit(List<String> instructions) {
        commands.check(instructions);

        // counted before sending, not from the replies kept: a submission whose retries run out
        // throws after reaching the hub, and the next one must not reuse its label in the record
        String label = "submission " + (++attempts);
        log.info("{}: {}", label, instructions);

        String reply = send.send(label, Map.of("instructions", List.copyOf(instructions)));
        responses.add(reply);
        return reply;
    }

    List<String> responses() {
        return List.copyOf(responses);
    }
}
