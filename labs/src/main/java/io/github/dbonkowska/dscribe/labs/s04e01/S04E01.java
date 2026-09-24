package io.github.dbonkowska.dscribe.labs.s04e01;

import io.github.dbonkowska.dscribe.agent.Agent;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.tool.Toolbox;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first lesson whose tool calls are writes. Every action changes state on the other side and
 * the change stays, so a wrong call is not merely refused: the next one starts from what it did.
 *
 * <p>The model is handed two tools and nothing about what the endpoint can do. It asks the endpoint
 * for help, makes the edits the system prompt describes, and sends the completion action itself — a
 * rejection comes back as a result it can act on, and the run ends on the reply that carries the flag.
 *
 * <p>The endpoint writes and returns nothing to write about: an edit needs a record's id, and only
 * the operator panel shows ids. So the second tool reads the panel, logged in, and never writes to
 * it. Code owns the shape of a write and where a read may go: see {@link ActionTool} and
 * {@link ReadTool}.
 */
public class S04E01 {

    /**
     * The model reads an API description and turns it into a sequence of writes, so this starts from
     * a tier that has earned flags on multi-step runs (s03e03, s03e05). A preference, not the last
     * word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and {@code openrouter.model} each
     * still win over it, so a cheaper tier is tried without an edit.
     */
    private static final String MODEL = "anthropic/claude-sonnet-4-6";

    /**
     * Counts model round-trips. Help, a few reads, the edits and a rejected completion or two fit
     * well inside it, so this is a backstop rather than the expected exit.
     */
    private static final int MAX_ITERATIONS = 40;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s04e01");
        TaskParams task = lesson.task(TaskParams.class);

        // Read and refused before the transcript opens: a blank prompt would buy a run of a model
        // that was told nothing.
        String system = lesson.prompt("system.md");
        if (system.isBlank()) {
            throw new IllegalStateException("system.md in the s04e01 lesson bundle is blank. Write it before running.");
        }
        Pattern flagPattern = Pattern.compile(task.flagPattern());

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("panel base url", task.panelBaseUrl());
        settings.put("pages", String.join(", ", task.pages().keySet()));
        settings.put("environment reset",
                "none sent — assumed cleared by the hub at session start; check the first transcript");

        // The login travels form-encoded, and an encoded password is a different string: redacting
        // only the raw one would leave it readable wherever it had a character that encodes.
        List<String> secrets = List.of(
                labsConfig.llm().apiKey(),
                labsConfig.hub().apiKey(),
                task.password(),
                URLEncoder.encode(task.password(), StandardCharsets.UTF_8));

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s04e01",
                settings,
                secrets)) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            ResilientHub resilient = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(List.of(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            // No reset, here or in a finally: the hub is assumed to clear the exercise's state when a
            // session starts. If the first transcript shows edits the run did not make, that
            // assumption is wrong, and a reset at startup is the fix.
            PanelClient panel = new PanelClient(
                    task.panelBaseUrl(), task.login(), task.password(), labsConfig.hub().apiKey(), transcript);
            Toolbox tools = new Toolbox(List.of(
                    new ActionTool(resilient, Set.copyOf(task.writablePages())).tool(task.action().name(), task.action().description()),
                    new ReadTool(panel, task.pages(), labsConfig.hub().apiKey())
                            .tool(task.read().name(), task.read().description())));

            List<Message> seed = List.of(new Message(Role.system, system));

            System.out.println("Model: " + llm.model());

            // Asked after every reply, so the run ends on the reply itself rather than one model call
            // later. Only the hub's replies count: a panel page is someone else's text, and a
            // flag-shaped string in one is not a result anyone earned.
            String actionName = task.action().name();
            String flag = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, (call, result) -> {
                if (!call.function().name().equals(actionName)) {
                    return Optional.empty();
                }
                Matcher found = flagPattern.matcher(result.text());
                return found.find() ? Optional.of(found.group()) : Optional.empty();
            });

            System.out.println(flag);
            transcript.outcome("Earned `" + flag + "`.");
            System.out.println("Transcript: " + transcript.file());
        }
    }
}
