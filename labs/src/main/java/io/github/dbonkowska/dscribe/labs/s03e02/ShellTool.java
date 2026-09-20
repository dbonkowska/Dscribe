package io.github.dbonkowska.dscribe.labs.s03e02;

import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        String label = "command " + sent;
        String body = post(label, command);

        // The number of tries is counted from what was made, never derived from the policy, so the
        // note the model reads is a fact about this command rather than a setting.
        int attempts = 1;
        int retries = 0;
        Duration waited = Duration.ZERO;
        Optional<String> code = matching(spec.transientCodes(), body);
        while (code.isPresent()) {
            Optional<Duration> backoff = policy.backoffAfter(attempts);
            if (backoff.isEmpty()) {
                break;
            }
            sleeper.await(backoff.get());
            waited = waited.plus(backoff.get());
            attempts++;
            retries++;
            body = post(label + " · retry " + retries, command);
            code = matching(spec.transientCodes(), body);
        }

        // A reply that recovered reads as an ordinary one. Only one still refused is annotated, and
        // only in what the model is shown: replies() keeps the body as the hub sent it.
        if (code.isPresent()) {
            return ToolOutput.of("[transient refusal " + code.get() + " · waited " + show(waited) + " over "
                    + retries + " retries]\n" + body);
        }
        return ToolOutput.of(body);
    }

    private String post(String label, String command) {
        String body = hub.post(label, spec.path(), Map.of(spec.commandKey(), command));
        replies.add(body);
        return body;
    }

    /** The first configured code the reply contains — the hub returns no status, only text. */
    private static Optional<String> matching(List<String> codes, String body) {
        return codes.stream().filter(body::contains).findFirst();
    }

    private static String show(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + "s" : duration.toMillis() + "ms";
    }
}
