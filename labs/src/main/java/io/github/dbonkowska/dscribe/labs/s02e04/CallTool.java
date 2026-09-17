package io.github.dbonkowska.dscribe.labs.s02e04;

import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Hands the model an API it learns about at run time: it picks an action and writes the
 * parameters, this sends them, and whatever comes back goes to the model verbatim.
 *
 * <p>Generic on purpose. The API describes itself, and its reply to the help action is in the
 * conversation, so the parameters are whatever that description says — a typed record per action
 * would only restate it, and go stale the moment the API changed. The parameters are therefore one
 * string of JSON, as in s01e05, which keeps {@code strict = true} intact where a schema accepting
 * arbitrary objects would not.
 *
 * <p>The action is the exception, and is narrowed rather than free. Choosing it chooses what
 * happens, and the API has one action with a side effect that the runner calls and the model must
 * never reach — so the verb comes from an allowlist, as a schema {@code enum}, and is checked again
 * here because a provider is not obliged to honour a schema.
 *
 * <p>Nothing is shared with s01e05's tool: a type imported from a finished lesson would freeze it.
 */
final class CallTool {

    /**
     * The whole schema the model sees.
     *
     * @param action one of the allowed actions
     * @param params the action's parameters as a JSON object, written as text — {@code "{}"} for none
     */
    record Call(String action, String params) {}

    /**
     * The facts about the exercise this needs, all supplied from outside the repository.
     *
     * @param path    where the API is posted to, under the hub's base URL
     * @param actions what the model may call
     */
    record Spec(String path, List<String> actions) {}

    /**
     * One POST to the hub, returning the body as it came — {@code HubClient::post}, and a recording
     * lambda in tests.
     */
    @FunctionalInterface
    interface Post {
        String post(String label, String path, Object body);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Keys the model may not write, and why. {@code action} would contradict the narrowed verb, so the
     * transcript's label and the API's effect could disagree — and one merge order would let the
     * parameters name an action the allowlist left out. {@code apikey} is merged in by the client.
     */
    /**
     * Where the API takes ids to fetch, and how long its stable ones are. Mechanism rather than task
     * content — they name the API's parameter shape, not anything the exercise asks about — so they
     * live here, beside the one rule that reads them.
     *
     * <p>The API accepts two kinds of id under this key: a positional one, and a stable hash. The
     * positional one renumbers on every call, because the source grows while the run reads it, and an
     * id copied from an earlier reply then names a different item — which the API returns without an
     * error. So it is refused here, before the wrong item is ever read, rather than left to a prompt
     * to discourage.
     */
    private static final String IDS = "ids";
    private static final int STABLE_ID_LENGTH = 32;

    private static final Map<String, String> RESERVED = Map.of(
            "action", "the action is chosen by the action argument, not inside the parameters",
            "apikey", "the key is added when the call is sent");

    private final Post hub;
    private final Spec spec;

    CallTool(Post hub, Spec spec) {
        this.hub = hub;
        this.spec = spec;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Call> tool(String name, String description) {
        ObjectNode schema = SchemaUtils.from(Call.class);
        ArrayNode allowed = SchemaUtils.at(schema, "/properties/action").putArray("enum");
        for (String action : spec.actions()) {
            allowed.add(action);
        }

        return new Tool<>(name, description, Call.class, args -> call(args.action(), args.params()), schema);
    }

    /**
     * Each refusal throws. {@code Toolbox} turns that into a tool result the model reads, and every
     * one of them is something to correct and call again.
     */
    private ToolOutput call(String action, String params) {
        if (!spec.actions().contains(action)) {
            throw new IllegalArgumentException(
                    "No action " + action + ". Nothing was sent. Call one of " + spec.actions() + ".");
        }

        ObjectNode body = parse(params);

        // checked before the action is merged, never after: merging first would overwrite the
        // model's key silently, and whatever it meant by it would go unrefused and unrecorded
        for (Map.Entry<String, String> reserved : RESERVED.entrySet()) {
            if (body.has(reserved.getKey())) {
                throw new IllegalArgumentException(
                        "params must not contain " + reserved.getKey() + " — " + reserved.getValue()
                                + ". Nothing was sent. Remove it and call again.");
            }
        }
        refusePositionalIds(body.get(IDS));
        body.put("action", action);

        return ToolOutput.of(hub.post("call · " + action, spec.path(), body));
    }

    /**
     * Every id under {@link #IDS}, whether one value or an array of them — each element, not only the
     * first, since a list mixing both kinds is exactly what a model copying from two replies writes.
     */
    private static void refusePositionalIds(JsonNode ids) {
        if (ids == null) {
            return;
        }
        for (JsonNode id : ids.isArray() ? ids.values() : List.of(ids)) {
            if (isPositional(id)) {
                throw new IllegalArgumentException(
                        IDS + " contains " + id + ", a positional id. Those renumber on every call, so"
                                + " this one would fetch a different item than the one it was copied from."
                                + " Nothing was sent. Use the " + STABLE_ID_LENGTH + "-character id instead.");
            }
        }
    }

    /**
     * An integer, or a digit-only string that is not a stable id's length. Length and digits
     * together rather than "all digits": a stable id is hex, and one that happens to contain no
     * letters is still stable.
     */
    private static boolean isPositional(JsonNode id) {
        if (id.isIntegralNumber()) {
            return true;
        }
        if (id.isString()) {
            String text = id.stringValue();
            return !text.isEmpty() && text.length() != STABLE_ID_LENGTH && text.chars().allMatch(Character::isDigit);
        }
        return false;
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
