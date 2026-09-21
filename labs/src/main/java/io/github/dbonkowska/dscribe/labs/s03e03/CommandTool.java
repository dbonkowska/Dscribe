package io.github.dbonkowska.dscribe.labs.s03e03;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Sends one command from a closed set and hands the environment's reply back untouched.
 *
 * <p>Deliberately not shaped like {@code s03e02}'s free-string tool. That one had no vocabulary to
 * narrow to: the environment described itself at run time. Here the set is known before the run
 * starts, so the schema carries it as an {@code enum} and a wrong command is unrepresentable rather
 * than refused after it has been paid for.
 *
 * <p>No prefix, no annotation, no count: the reply is the only feedback the environment gives and
 * the model reads it as it came. What ends the run is not decided here. The agent asks an observer
 * about each reply, so this stays a pipe.
 */
final class CommandTool {

    /** The whole schema the model sees: one command, out of a set narrowed from the bundle. */
    record Command(String command) {}

    /**
     * The facts about the exercise this needs, all supplied from outside the repository.
     *
     * @param commandKey the field name the hub expects the command under, the exercise's word and
     *                   not ours
     * @param commands   the commands that exist for the model, which become the schema's vocabulary
     */
    record Spec(String commandKey, List<String> commands) {}

    private final ResilientHub hub;
    private final Spec spec;

    CommandTool(ResilientHub hub, Spec spec) {
        this.hub = hub;
        this.spec = spec;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Command> tool(String name, String description) {
        ObjectNode schema = SchemaUtils.from(Command.class);
        ArrayNode allowed = SchemaUtils.at(schema, "/properties/command").putArray("enum");
        for (String command : spec.commands()) {
            allowed.add(command);
        }

        return new Tool<>(name, description, Command.class,
                args -> ToolOutput.of(hub.call(name, Map.of(spec.commandKey(), args.command()))),
                schema);
    }
}
