package io.github.dbonkowska.dscribe.labs.s02e03;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.tokens.TokenBudget;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sends a selection of map entries as the condensed text, after making sure it can be sent.
 *
 * <p>The model chooses and nothing else. It never writes a line: each entry is rendered by
 * {@link EventMap}, so a timestamp, a severity or an identifier cannot be malformed on the way out,
 * and the size is measured here, before the hub is involved, so an attempt that could not fit costs
 * one iteration rather than a round the hub spends saying so.
 *
 * <p>Every call replaces the last submission whole. There is no stored draft to add to or remove
 * from: a draft kept here and a draft the model remembers are two copies that drift, and the model
 * then reasons about one that does not exist. Resending a few dozen short ids is the cheaper trade.
 */
final class SubmitTool {

    private static final Logger log = LoggerFactory.getLogger(SubmitTool.class);

    /** The whole schema the model sees: which entries, out of the ids the map has. */
    record Selection(List<String> ids) {}

    /**
     * What an attempt comes back as.
     *
     * @param tokens       the measured size of what was sent
     * @param effectiveCap the most that may be sent, so the room left is readable from one result
     * @param response     the hub's reply, word for word — its account of what is missing is the
     *                     only guidance the next attempt gets, and no count of what it accepted is
     *                     kept beside it
     */
    record Attempt(int tokens, int effectiveCap, String response) {}

    /**
     * The facts about the exercise this needs, all supplied from outside the repository.
     *
     * @param answerKey  the field the hub expects the text under
     * @param lineFormat how one entry is written, filled by {@link EventMap#renderSubmission}
     * @param budget     the hub's limit and the part of it held back
     */
    record Spec(String answerKey, String lineFormat, TokenBudget budget) {}

    private final ResilientHub hub;
    private final EventMap map;
    private final Spec spec;

    /** Every body the hub returned, so a reported result can be checked against what was said. */
    private final List<String> responses = new ArrayList<>();

    private int sent;

    SubmitTool(ResilientHub hub, EventMap map, Spec spec) {
        this.hub = hub;
        this.map = map;
        this.spec = spec;
    }

    /**
     * Name and description come from the lesson bundle: they are prompt surface.
     *
     * <p>The ids are narrowed to the map's own. They are a closed set, but unlike every earlier
     * vocabulary they come from neither the bundle nor a constant — they exist only once the source
     * has been read, which is why the schema is built from the map rather than from
     * {@code TaskParams}.
     */
    Tool<Selection> tool(String name, String description) {
        ObjectNode schema = SchemaUtils.from(Selection.class);
        ArrayNode allowed = SchemaUtils.at(schema, "/properties/ids/items").putArray("enum");
        for (Event event : map.events()) {
            allowed.add(event.id());
        }

        return new Tool<>(name, description, Selection.class, args -> ToolOutput.of(submit(args.ids())), schema);
    }

    /**
     * One attempt: render, measure, and send only if it fits.
     *
     * <p>Package-visible because the runner makes the first attempt itself, before the model is
     * involved, and it has to be rendered, measured and recorded exactly as the model's are.
     *
     * <p>Each refusal throws. {@code Toolbox} turns that into a tool result the model reads, and
     * every one of them is something to correct and try again — which is what distinguishes them
     * from a refusal meaning "stop".
     */
    Attempt submit(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException(
                    "The selection is empty, so there is nothing to send. List the ids of every entry"
                            + " the submission should contain.");
        }

        // restating a selection can repeat an id; the submission must not repeat the line
        String logs = map.renderSubmission(ids.stream().distinct().toList(), spec.lineFormat());

        int measured = spec.budget().measure(logs);
        if (!spec.budget().fits(measured)) {
            throw new IllegalArgumentException(
                    "That selection measures " + measured + " tokens, and at most "
                            + spec.budget().effectiveCap() + " can be sent. Nothing was sent."
                            + " Remove entries and submit the complete list again.");
        }

        // counted before sending, not from the responses kept: an attempt whose retries run out
        // throws after reaching the hub, and the next one must not reuse its label in the record
        String label = "attempt " + (++sent);
        log.info("{}: {} entries, {} tokens", label, logs.lines().count(), measured);

        String response = hub.call(label, Map.of(spec.answerKey(), logs));
        responses.add(response);

        return new Attempt(measured, spec.budget().effectiveCap(), response);
    }

    List<String> responses() {
        return List.copyOf(responses);
    }
}
