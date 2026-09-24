package io.github.dbonkowska.dscribe.labs.s04e01;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Hands the model an endpoint it learns about at run time: it names an action and writes the
 * parameters, this sends them to verify as the answer, and whatever comes back goes to the model
 * verbatim.
 *
 * <p>Generic on purpose, and less narrowed than s02e04's tool it was copied from. There the actions
 * were known up front and came from an allowlist; here the endpoint's own help action is the only
 * account of what exists, so the action is a free string and the schema carries no {@code enum}.
 * The parameters are one string of JSON, which keeps {@code strict = true} intact where a schema
 * accepting arbitrary objects would not.
 *
 * <p>Every action is a write that stays, so a malformed call is refused before it is sent rather
 * than left for the hub to interpret. Nothing is shared with s02e04's tool: a type imported from a
 * finished lesson would freeze it.
 */
final class ActionTool {

    /**
     * The whole schema the model sees.
     *
     * @param action what to do, in the endpoint's own words
     * @param params the action's parameters as a JSON object, written as text — {@code "{}"} for none
     */
    record Call(String action, String params) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The one key the model may not write inside the parameters. It would contradict the action
     * argument, so the transcript's label and the write the hub makes could disagree. {@code apikey}
     * is not reserved: the hub key sits beside the answer in the envelope, not inside it, so there is
     * nothing for it to collide with.
     */
    private static final String ACTION = "action";

    /**
     * Where the endpoint takes the page a write lands on. Mechanism, not task content: it names the
     * endpoint's parameter shape, and the pages themselves come from the bundle.
     */
    private static final String PAGE = "page";

    private final ResilientHub hub;
    private final Set<String> writablePages;

    ActionTool(ResilientHub hub, Set<String> writablePages) {
        this.hub = hub;
        this.writablePages = writablePages;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Call> tool(String name, String description) {
        return new Tool<>(name, description, Call.class, args -> send(args.action(), args.params()));
    }

    /**
     * Each refusal throws. {@code Toolbox} turns that into a tool result the model reads, and every
     * one of them is something to correct and call again.
     */
    private ToolOutput send(String action, String params) {
        ObjectNode answer = parse(params);

        // checked before the action is merged, never after: merging first would overwrite the
        // model's key silently, and whatever it meant by it would go unrefused and unrecorded
        if (answer.has(ACTION)) {
            throw new IllegalArgumentException(
                    "params must not contain " + ACTION + " — the action is chosen by the action argument,"
                            + " not inside the parameters. Nothing was sent. Remove it and call again.");
        }
        refuseUnwritablePage(answer.get(PAGE));
        answer.put(ACTION, action);

        return ToolOutput.of(hub.call("action · " + action, answer));
    }

    /**
     * A call without a page passes: the completion and help actions carry none, and the endpoint
     * refuses an edit that lacks one. A page that is not a string cannot be one of the allowed names,
     * so it is refused rather than waved through as "no page".
     *
     * <p>Why the guard exists: a model that could only see a record truncated overwrote it, hoping the
     * reply would show what had been there, and the record held the one thing the run needed. Which
     * pages a run may change is the exercise's to say; anything else is refused before it is sent.
     */
    private void refuseUnwritablePage(JsonNode page) {
        if (page == null) {
            return;
        }
        if (!page.isString() || !writablePages.contains(page.stringValue())) {
            throw new IllegalArgumentException(
                    "This run may not write to page " + page + ". Nothing was sent. The pages it may"
                            + " change are " + writablePages + "; the others can only be read.");
        }
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
