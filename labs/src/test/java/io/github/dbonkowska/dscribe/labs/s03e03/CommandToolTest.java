package io.github.dbonkowska.dscribe.labs.s03e03;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.HubSend;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The narrowing is the reason this tool is shaped the way it is: the command is one of a closed set,
 * so the schema can carry the set and a wrong command is unrepresentable. An enum that silently
 * did not apply would read as working — the model would simply be free to send anything, and the
 * run would find out from the environment.
 *
 * <p>Fixtures are invented — the commands and the key belong to no lesson.
 */
class CommandToolTest {

    private static final List<String> COMMANDS = List.of("go", "hold", "back");
    private static final String KEY = "cmd";

    /** What the fake hub was handed, in order. */
    private final List<Object> sent = new ArrayList<>();

    @Test
    void offersOnlyTheCommandsTheExerciseDefines(@TempDir Path root) {
        List<String> allowed = new ArrayList<>();
        commandTool(root).spec().function().parameters()
                .at("/properties/command/enum")
                .forEach(node -> allowed.add(node.stringValue()));

        assertEquals(COMMANDS, allowed);
    }

    /** The key the hub wants comes from the bundle; nothing here knows what it is called. */
    @Test
    void sendsTheCommandUnderTheKeyTheExerciseNames(@TempDir Path root) {
        commandTool(root).handler().apply(new CommandTool.Command("hold"));

        assertEquals(Map.of(KEY, "hold"), sent.getFirst());
    }

    /** The observer and the model both read this, so it must be what the hub said and nothing more. */
    @Test
    void handsBackTheHubsReplyWordForWord(@TempDir Path root) {
        ToolOutput output = commandTool(root).handler().apply(new CommandTool.Command("go"));

        assertEquals("{\"n\":1}", output.result());
    }

    private Tool<CommandTool.Command> commandTool(Path root) {
        return new CommandTool(hub(root), new CommandTool.Spec(KEY, COMMANDS)).tool("send", "sends one");
    }

    private ResilientHub hub(Path root) {
        HubSend sender = (label, taskName, answer) -> {
            sent.add(answer);
            return new HubResponse(200, TestHeaders.of(Map.of()), "{\"n\":" + sent.size() + "}");
        };

        return new ResilientHub(
                sender,
                "x-task",
                RetryPolicy.defaults(),
                new RateLimitHeaders(List.of(), Duration.ofSeconds(60)),
                wait -> {},
                RunTranscript.open(root.resolve("logs"), "x01", Map.of(), List.of()));
    }
}
