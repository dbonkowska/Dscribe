package io.github.dbonkowska.dscribe.labs.s05e03;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;

import java.util.List;
import java.util.Map;

/**
 * Hands the model a shell on the hub's machine: it writes a command, {@link ShellGuard} decides
 * whether it may go, and the hub's reply comes back as it was sent.
 *
 * <p>Nothing here bounds the output. The hub refuses any output over its own cap and says so in the
 * reply, naming the cap, so the model never sees a reply cut short and is always told why one is
 * missing. A local cut would never fire. The probe of 2026-11-03 is the evidence.
 *
 * <p>A flag in this tool's reply never ends the run: the files it reads are someone else's text, and
 * a flag-shaped string among them is not a result anyone earned. Only the report tool's reply can end
 * it.
 *
 * <p>Copied in spirit from s03e02's shell tool and s04e01's redaction, not imported: a type from a
 * finished lesson would freeze it.
 */
final class ShellTool {

    /** What stands where a credential was, so the model sees that something was removed. */
    static final String REDACTED = "[redacted]";

    /**
     * The whole schema the model sees.
     *
     * @param cmd one shell command, or several joined with {@code |}, {@code ;}, {@code &&} or
     *            {@code ||}
     */
    record Call(String cmd) {}

    private final ResilientHub hub;
    private final List<String> secrets;

    /**
     * @param secrets every credential the session carries, the same list the transcript redacts:
     *                the output is printed by the server, and whatever it prints goes to the model
     *                provider on every later turn
     */
    ShellTool(ResilientHub hub, List<String> secrets) {
        this.hub = hub;
        this.secrets = List.copyOf(secrets);
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Call> tool(String name, String description) {
        return new Tool<>(name, description, Call.class, args -> run(args.cmd()));
    }

    /**
     * The guard's refusal throws, which {@code Toolbox} turns into a tool result the model reads.
     * Reads are harmless twice, so the retries {@link ResilientHub} makes need no caveat here.
     */
    private ToolOutput run(String command) {
        ShellGuard.check(command);
        return ToolOutput.of(redact(hub.call("shell · " + command, Map.of(TaskParams.CMD_KEY, command))));
    }

    /** A blank secret is not replaced: it would match between every character. */
    private String redact(String reply) {
        String redacted = reply;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        return redacted;
    }
}
