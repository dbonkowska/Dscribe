package io.github.dbonkowska.dscribe.labs.s03e02;

import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     * @param causedCodes    reply fragments meaning "a command caused this"
     * @param repeatThreshold how many times in a row the same command is sent before a repeat is refused.
     *                        Mechanism rather than task content, so the runner supplies a constant
     * @param maxReplyChars the most of one reply the model is handed. A file can come back as megabytes, which
     *                      would overflow its context; the start and end are kept. Mechanism, not task content
     * @param ignore         how to recognise a reply that lists paths to avoid, so the guard can learn them
     * @param culpritPointer  a JSON pointer to where a caused refusal names the command it blames, or
     *                        blank when the exercise's replies name none
     */
    record Spec(
            String path,
            String commandKey,
            List<String> forbidden,
            List<String> transientCodes,
            List<String> causedCodes,
            int repeatThreshold,
            int maxReplyChars,
            Guard.Learning ignore,
            String culpritPointer) {}

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
    private String lastNormalised;
    private int consecutive;
    private String lastReply;

    ShellTool(Post hub, Spec spec, RetryPolicy policy, Sleeper sleeper) {
        this.hub = hub;
        this.spec = spec;
        this.guard = new Guard(spec.forbidden(), spec.ignore());
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

        // After the guard, before the send: a refused command is not a repeat, and a refused repeat is not
        // a send, so the count is what was really sent and not how often the model asked.
        String normalised = Commands.normalise(command);
        boolean repeat = normalised.equals(lastNormalised);
        if (repeat && consecutive >= spec.repeatThreshold()) {
            throw new IllegalArgumentException(
                    "This command has already been sent " + consecutive + " times in a row with nothing"
                            + " changing in the replies. Nothing was sent. Try a different approach.");
        }
        ToolOutput output = send(command);

        // A repeat only counts while the environment keeps answering the same way. A different reply
        // is progress, as when one command climbs a directory at a time, and restarts the run. The raw
        // reply is compared, not what the model was shown, so a note cannot make two replies differ.
        String reply = replies.getLast();
        consecutive = repeat && reply.equals(lastReply) ? consecutive + 1 : 1;
        lastNormalised = normalised;
        lastReply = reply;
        return output;
    }

    /** Sends one command, and waits out or annotates a refusal the environment answers with. */
    private ToolOutput send(String command) {
        sent++;
        String label = "command " + sent;
        String body = post(label, command);

        // The number of tries is counted from what was made, never derived from the policy, so the
        // note the model reads is a fact about this command rather than a setting.
        int attempts = 1;
        int retries = 0;
        Duration waited = Duration.ZERO;
        while (true) {
            // Every reply received is classified, the retry's as much as the first: a busy reply is
            // retried, and the retry can land on a ban.
            Optional<String> caused = matching(spec.causedCodes(), body);
            if (caused.isPresent()) {
                // Resending would meet the same refusal, so wait once and hand it back.
                Duration wait = policy.backoffAfter(1).orElse(Duration.ZERO);
                if (!wait.isZero()) {
                    sleeper.await(wait);
                }
                return ToolOutput.of("[caused refusal " + caused.get() + " · waited "
                        + show(waited.plus(wait)) + " · not retried · " + culprit(body, command) + "]\n"
                        + shown(body));
            }

            Optional<String> code = matching(spec.transientCodes(), body);
            // A reply that recovered reads as an ordinary one. Only one still refused is annotated, and
            // only in what the model is shown: replies() keeps the body as the hub sent it.
            if (code.isEmpty()) {
                return ToolOutput.of(shown(body));
            }
            Optional<Duration> backoff = policy.backoffAfter(attempts);
            if (backoff.isEmpty()) {
                return ToolOutput.of("[transient refusal " + code.get() + " · waited " + show(waited)
                        + " over " + retries + " retries]\n" + shown(body));
            }
            sleeper.await(backoff.get());
            waited = waited.plus(backoff.get());
            attempts++;
            retries++;
            body = post(label + " · retry " + retries, command);
        }
    }

    /**
     * What a caused-refusal note says about the command. Only the reply knows which command it
     * blames: this hub's refusal is often about the command just sent, and sometimes about an earlier
     * one it names, so guessing from the order of sends would state something the code cannot know.
     * When the reply names none, the note says only which command it was answering.
     */
    private String culprit(String body, String command) {
        String pointer = spec.culpritPointer();
        if (pointer != null && !pointer.isBlank()) {
            try {
                JsonNode named = MAPPER.readTree(body).at(pointer);
                if (named.isString() && !named.asString().isBlank()) {
                    return "caused by: " + named.asString();
                }
            } catch (JacksonException e) {
                // not JSON, so it names nothing
            }
        }
        return "refused: " + command;
    }

    /**
     * What the model reads of a reply. A reply within the cap is handed over as it is. A longer one
     * keeps its start and its end, with a line between saying what was cut and how long the whole
     * was: the end is kept because a program's output tends to finish with what it produced. Only
     * this view is cut — {@code replies()} and the transcript keep the whole reply.
     */
    private String shown(String body) {
        int max = spec.maxReplyChars();
        if (body.length() <= max) {
            return body;
        }
        int half = max / 2;
        int omitted = body.length() - 2 * half;
        return body.substring(0, half)
                + "\n[... " + omitted + " of " + body.length() + " characters omitted: only the start and"
                + " the end of this reply are shown. The whole reply was recorded. ...]\n"
                + body.substring(body.length() - half);
    }

    private String post(String label, String command) {
        String body = hub.post(label, spec.path(), Map.of(spec.commandKey(), command));
        replies.add(body);
        // Every reply, not only a successful one: what a file lists is what the run now knows to avoid,
        // and the command that touches it may already be written, waiting behind this one.
        guard.learn(body);
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
