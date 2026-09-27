package io.github.dbonkowska.dscribe.labs.s04e05;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Hands the model an endpoint it learns about at run time: it names a tool, optionally an action,
 * and writes the parameters; this sends them to verify as the answer, and whatever comes back goes
 * to the model verbatim.
 *
 * <p>Copied from s04e03's {@code ActionTool} rather than imported: a type from a finished lesson
 * would freeze it. The endpoint here addresses a tool first and an action within it second, so the
 * envelope gains a {@code tool} and the action becomes optional. Neither carries an {@code enum}:
 * the endpoint's own help is the only account of what exists. The parameters are one string of
 * JSON, which keeps {@code strict = true} intact where a schema accepting arbitrary objects would
 * not.
 */
final class ApiTool {

    /**
     * The whole schema the model sees.
     *
     * @param tool   which of the endpoint's tools to call, in its own words
     * @param action the action within that tool, or empty for a tool that takes none
     * @param params the call's parameters as a JSON object, written as text — {@code "{}"} for none
     */
    record Call(String tool, String action, String params) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The keys this layer writes into the answer. The model may not write them inside the
     * parameters: each would contradict its argument, so the transcript's label and what the hub
     * does could disagree. {@code apikey} is not reserved: the hub key sits beside the answer in the
     * envelope, not inside it, so there is nothing for it to collide with.
     */
    private static final String TOOL = "tool";
    private static final String ACTION = "action";

    /** The parameter a delete names its order by: protocol vocabulary, as the envelope keys are. */
    private static final String ID = "id";

    /** How a query reply reports its size and its cap: protocol vocabulary, seen in the probe. */
    private static final String COUNT = "count";
    private static final String LIMIT = "limit";

    private final ResilientHub hub;
    private final String resetTool;
    private final TaskParams.Orders orders;
    private final Set<String> seeded;
    private final String databaseTool;

    ApiTool(ResilientHub hub, String resetTool, TaskParams.Orders orders, Set<String> seeded, String databaseTool) {
        this.hub = hub;
        this.resetTool = resetTool;
        this.orders = orders;
        this.seeded = Set.copyOf(seeded);
        this.databaseTool = databaseTool;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Call> tool(String name, String description) {
        return new Tool<>(name, description, Call.class, args -> send(args.tool(), args.action(), args.params()));
    }

    /**
     * Each refusal throws. {@code Toolbox} turns that into a tool result the model reads, and every
     * one of them is something to correct and call again.
     */
    private ToolOutput send(String tool, String action, String params) {
        // decided by the tool alone, before the parameters are read: a bad-JSON refusal here would
        // invite the model to fix the JSON and send the reset again. Matched stripped and
        // case-folded, as s04e03's environment matched its own reset; whether this one folds is
        // unprobed, and refusing a spelling it would have rejected anyway costs nothing.
        if (resetTool.equalsIgnoreCase(tool.strip())) {
            throw new IllegalArgumentException(
                    "The tool " + tool + " is not available to you. The run already sent it once, at"
                            + " startup, and sending it again would delete every order created since."
                            + " Nothing was sent. To undo an order you created, delete that order.");
        }
        ObjectNode answer = parse(params);

        // After the parse, because it needs the parsed id; before the reserved keys, because no fix
        // to the call makes a seeded delete acceptable, and a reserved-key refusal first would have
        // the model remove the key and send the same delete.
        if (isDelete(tool, action)) {
            JsonNode id = answer.get(ID);
            // By form, before the seeded check: a list or an object would slip past a check that
            // compares one value, and a delete names one order anyway.
            if (id != null && !id.isValueNode()) {
                throw new IllegalArgumentException(
                        "A delete takes one order id, not " + id.getNodeType() + ". Nothing was sent."
                                + " Delete one order per call.");
            }
            if (id != null && seeded.contains(id.asString().strip())) {
                throw new IllegalArgumentException(
                        "The order " + id.asString().strip() + " existed before this run and is left in"
                                + " place: it may not be deleted. Nothing was sent. Only orders you created"
                                + " can be deleted.");
            }
        }

        // checked before the merge, never after: merging first would overwrite the model's key
        // silently, and whatever it meant by it would go unrefused and unrecorded
        for (String reserved : new String[] {TOOL, ACTION}) {
            if (answer.has(reserved)) {
                throw new IllegalArgumentException(
                        "params must not contain " + reserved + " — it is chosen by the " + reserved
                                + " argument, not inside the parameters. Nothing was sent. Remove it and"
                                + " call again.");
            }
        }
        answer.put(TOOL, tool);
        boolean hasAction = action != null && !action.isBlank();
        if (hasAction) {
            answer.put(ACTION, action);
        }

        String reply = hub.call(tool + (hasAction ? " · " + action : ""), answer);
        return ToolOutput.of(databaseTool.equalsIgnoreCase(tool.strip()) ? noteIfCapped(reply) : reply);
    }

    /**
     * A query reply is capped at a row limit, and a capped reply looks exactly like a complete one.
     * Where it came back at the cap, the model is told so — advice, not a refusal: a query that asked
     * for exactly that many rows is legitimate. Any reply that does not carry both numbers passes
     * through untouched, since it tells nothing about a cap.
     */
    private static String noteIfCapped(String reply) {
        JsonNode node;
        try {
            node = MAPPER.readTree(reply);
        } catch (JacksonException e) {
            return reply;
        }
        JsonNode count = node.get(COUNT);
        JsonNode limit = node.get(LIMIT);
        if (count == null || limit == null || !count.isNumber() || !limit.isNumber()
                || count.asLong() != limit.asLong()) {
            return reply;
        }
        return reply + "\n\nNote from the run: this reply holds " + count.asLong() + " rows, exactly its"
                + " limit, so the result may be cut off. Narrow the query, or page through it with"
                + " LIMIT and OFFSET, until a reply comes back under the limit.";
    }

    /**
     * Tool and action are matched stripped and case-folded, like the reset, and the id is compared
     * stripped: a padded or recased delete is the same delete, and refusing one the hub would reject
     * anyway costs nothing.
     */
    private boolean isDelete(String tool, String action) {
        return orders.tool().equalsIgnoreCase(tool.strip())
                && action != null
                && orders.delete().equalsIgnoreCase(action.strip());
    }

    /**
     * Refused as text the model can act on rather than as a parse or cast failure. Both would reach
     * it as a tool result; only this one says what to send instead.
     */
    private static ObjectNode parse(String params) {
        JsonNode node;
        try {
            node = MAPPER.readTree(params);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "params is not valid JSON: " + e.getOriginalMessage()
                            + ". Nothing was sent. Write the parameters as a JSON object, {} for none.", e);
        }
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalArgumentException(
                "params must be a JSON object, not " + node.getNodeType()
                        + ". Nothing was sent. Write the parameters as a JSON object, {} for none.");
    }
}
