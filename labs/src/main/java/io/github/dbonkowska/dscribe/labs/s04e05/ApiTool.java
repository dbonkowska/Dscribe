package io.github.dbonkowska.dscribe.labs.s04e05;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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

    private final ResilientHub hub;

    ApiTool(ResilientHub hub) {
        this.hub = hub;
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
        ObjectNode answer = parse(params);

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

        return ToolOutput.of(hub.call(tool + (hasAction ? " · " + action : ""), answer));
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
