package io.github.dbonkowska.dscribe.labs.s03e02;

import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hands the model a shell it cannot see into: it writes a command, this sends it, and what comes
 * back goes to the model.
 *
 * <p>Every reply is also kept exactly as the hub sent it, in {@link #replies()}. What the model is
 * shown may carry a note this tool wrote about the reply; what is kept never does, because the code
 * the run submits is read back out of these and a note must not be able to change what is found.
 *
 * <p>Nothing is shared with an earlier lesson's tool: a type imported from a finished lesson would
 * freeze it.
 */
final class ShellTool {

    /**
     * The whole schema the model sees.
     *
     * @param command the command to run
     */
    record Command(String command) {}

    /**
     * The facts about the exercise this needs, all supplied from outside the repository.
     *
     * @param path           where the shell is posted to, under the hub's base URL
     * @param commandKey     the body key a command travels under
     * @param forbidden      roots a command must not address
     * @param transientCodes reply fragments meaning "try again shortly"
     * @param causedCodes    reply fragments meaning "your previous command caused this"
     */
    record Spec(
            String path,
            String commandKey,
            List<String> forbidden,
            List<String> transientCodes,
            List<String> causedCodes) {}

    /**
     * One POST to the hub, returning the body as it came — {@code HubClient::post}, and a recording
     * lambda in tests.
     */
    @FunctionalInterface
    interface Post {
        String post(String label, String path, Object body);
    }

    private final Post hub;
    private final Spec spec;
    private final Guard guard;
    private final RetryPolicy policy;
    private final Sleeper sleeper;

    private final List<String> replies = new ArrayList<>();
    private int sent;

    ShellTool(Post hub, Spec spec, RetryPolicy policy, Sleeper sleeper) {
        this.hub = hub;
        this.spec = spec;
        this.guard = new Guard(spec.forbidden());
        this.policy = policy;
        this.sleeper = sleeper;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Command> tool(String name, String description) {
        return new Tool<>(
                name, description, Command.class, args -> run(args.command()), SchemaUtils.from(Command.class));
    }

    /** Every reply the hub gave, raw and in order — what the submit tool reads its code from. */
    List<String> replies() {
        return List.copyOf(replies);
    }

    /**
     * Refusals throw. {@code Toolbox} turns that into a tool result the model reads, and every one of
     * them is something to correct and call again.
     */
    private ToolOutput run(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is blank. Nothing was sent. Write a command to run.");
        }

        // before anything is counted or sent: a refused command must cost nothing
        guard.violated(command).ifPresent(root -> {
            throw new IllegalArgumentException(
                    "The command addresses " + root + ", which is off limits. Nothing was sent. Choose a"
                            + " command that does not touch it.");
        });

        sent++;
        String body = hub.post("command " + sent, spec.path(), Map.of(spec.commandKey(), command));
        replies.add(body);
        return ToolOutput.of(body);
    }
}
