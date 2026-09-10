package io.github.dbonkowska.dscribe.labs.s01e05;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Hands the model the API and gets out of the way: it writes the request body, this sends it, and
 * whatever comes back goes to the model verbatim.
 *
 * <p>The argument is a string of JSON rather than a typed record, which is the opposite of what
 * every other tool here does. It has to be. The API documents itself at run time, so the names and
 * parameters this tool will be called with do not exist when it is written — there is no
 * vocabulary to narrow a schema to, and a record would only be a guess the model has to work
 * around. Keeping it one string also keeps {@code strict = true} intact everywhere else, since a
 * schema that accepted arbitrary objects is precisely what strict mode forbids.
 *
 * <p>What replaces the lost validation is the cost of being wrong. A body that will not parse is
 * refused here, before any request goes out, so a malformed call costs one model iteration and
 * nothing from the request budget — the currency that is actually scarce.
 *
 * <p>Every response is kept, so the runner can check a reported result against what the hub
 * really said rather than trusting the model to quote it honestly.
 */
final class CallTool {

    /** The whole schema the model sees: one field, holding the request body it composed. */
    record Call(String json) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResilientHub hub;
    private final List<String> responses = new ArrayList<>();

    CallTool(ResilientHub hub) {
        this.hub = hub;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Call> tool(String name, String description) {
        return new Tool<>(name, description, Call.class, args -> send(name, args.json()));
    }

    /** Every body the hub actually returned, in the order they arrived. */
    List<String> responses() {
        return List.copyOf(responses);
    }

    /**
     * Throws rather than reporting, on purpose: {@code Toolbox} turns anything thrown here into a
     * tool result the model reads, so a bad body costs one iteration and the model corrects it —
     * the same recovery path a bad URL had in the previous lesson.
     */
    private ToolOutput send(String label, String json) {
        JsonNode body = MAPPER.readTree(json);
        if (!body.isObject()) {
            throw new IllegalArgumentException(
                    "The request body must be a JSON object, not " + body.getNodeType()
                            + ". Send the object itself, as text.");
        }

        String response = hub.call(label, body);
        responses.add(response);
        return ToolOutput.of(response);
    }
}
