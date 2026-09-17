package io.github.dbonkowska.dscribe.labs.s02e04;

import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

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

        ObjectNode body = (ObjectNode) MAPPER.readTree(params);
        body.put("action", action);

        return ToolOutput.of(hub.post("call · " + action, spec.path(), body));
    }
}
