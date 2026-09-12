package io.github.dbonkowska.dscribe.labs.s02e02;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
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
 * One call, one change to state the run does not own — and a count of how many are left.
 *
 * <p>Deliberately not shaped like {@code s01e05}'s free-JSON tool. That one had no vocabulary to
 * narrow to: the API documented itself at run time, so the names it would be called with did not
 * exist when it was written. Here they do, and they are a closed set, so the schema carries them
 * as an {@code enum} and a wrong address is unrepresentable rather than refused after it has been
 * paid for. Nothing is shared between the two: a tool imported from a finished lesson would
 * freeze that lesson's types permanently.
 *
 * <p>The budget lives here rather than in the agent loop because that loop counts model
 * round-trips, and one round-trip may ask for any number of moves at once.
 */
final class MoveTool {

    private static final Logger log = LoggerFactory.getLogger(MoveTool.class);

    /** The whole schema the model sees: one address, out of a set narrowed from the bundle. */
    record Move(String target) {}

    /**
     * The facts about the exercise this needs, all supplied from outside the repository.
     *
     * @param answerKey the field name the hub expects the address under — the exercise's word,
     *                  not ours, so nothing here is named after it
     * @param positions the addresses that exist, which become the schema's vocabulary
     * @param maxMoves  how many calls this may spend before it stops sending
     */
    record Spec(String answerKey, List<String> positions, int maxMoves) {}

    private final ResilientHub hub;
    private final Spec spec;

    /** Every body the hub returned, so a reported result can be checked against what was said. */
    private final List<String> responses = new ArrayList<>();

    private int spent;

    MoveTool(ResilientHub hub, Spec spec) {
        this.hub = hub;
        this.spec = spec;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Move> tool(String name, String description) {
        ObjectNode schema = SchemaUtils.from(Move.class);
        ArrayNode allowed = SchemaUtils.at(schema, "/properties/target").putArray("enum");
        for (String position : spec.positions()) {
            allowed.add(position);
        }

        return new Tool<>(name, description, Move.class, args -> move(name, args.target()), schema);
    }

    List<String> responses() {
        return List.copyOf(responses);
    }

    /**
     * Returns the refusal rather than throwing it. A spent budget is a fact the model should read
     * and act on — by reporting what it has, or by stopping — where anything thrown reaches it as
     * "Tool failed:", which reads like something worth trying again.
     */
    private ToolOutput move(String label, String target) {
        if (spent >= spec.maxMoves()) {
            log.info("budget spent: {} of {}", spent, spec.maxMoves());
            return ToolOutput.of(
                    "No moves left: " + spent + " of " + spec.maxMoves() + " already sent."
                            + " Nothing further will be sent. Report what you have.");
        }

        spent++;
        String response = hub.call(label, Map.of(spec.answerKey(), target));
        responses.add(response);
        return ToolOutput.of(response);
    }
}
