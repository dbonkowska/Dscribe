package io.github.dbonkowska.dscribe.labs.s05e03;

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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A model investigating a server it reaches only through a shell, and answering by having the server
 * print the answer.
 *
 * <p>The exploration is the model's: which files exist, how they link, which record is the one. Code
 * holds what it can check itself. {@link ShellGuard} lets through only commands it can identify as
 * read-only, because the shell's user may run {@code tar} as root. {@link ReportTool} takes the values
 * as the records state them, moves the date by the bundle's shift, and prints the answer, so the
 * calendar arithmetic and the formatting are never the model's.
 *
 * <p>The run ends on a flag in the report tool's reply and nowhere else. Nothing is reset, at startup
 * or in a {@code finally}: the run changes nothing on the server, by construction.
 *
 * <p>Nothing here names the task: the data path, the shift, the answer's fields, the tool names and
 * the prompts are all in the lesson bundle.
 */
public class S05E03 {

    private static final String LESSON = "s05e03";

    /**
     * The tier that earned s04e03 to s04e05. A preference, not the last word —
     * {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and {@code openrouter.model} each still win
     * over it.
     */
    private static final String MODEL = "google/gemini-3.8-flash";

    /**
     * Counts model round-trips. Sampling three files, a few searches, following two ids and a report
     * fit well inside it, with room for a wrong report and a second look.
     */
    private static final int MAX_ITERATIONS = 50;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    /** The one slot {@code user.md} fills: where the data lies. */
    private static final String DATA_SLOT = "{dataDir}";

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, LESSON);
        TaskParams task = lesson.task(TaskParams.class);
        Pattern flagPattern = Pattern.compile(task.flagPattern());

        // Read and refused before the transcript opens: a blank prompt would buy a run of a model that
        // was told nothing, and a user.md without its slot would never say where to look.
        String system = lesson.requiredPrompt("system.md");
        String userTemplate = lesson.requiredPrompt("user.md");
        int slots = userTemplate.split(Pattern.quote(DATA_SLOT), -1).length - 1;
        if (slots != 1) {
            throw new IllegalStateException(
                    "user.md has " + DATA_SLOT + " " + slots + " times; it needs it exactly once, for the"
                            + " data directory.");
        }

        List<String> secrets = List.of(labsConfig.hub().apiKey(), labsConfig.llm().apiKey());

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("date shift", task.dateShiftDays() + " days, applied by the report tool");

        try (RunTranscript transcript =
                RunTranscript.open(labsConfig.dataDir().resolve("logs"), LESSON, settings, secrets)) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            ResilientHub resilient = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(List.of(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            String reportName = task.report().name();
            Toolbox tools = new Toolbox(List.of(
                    new ShellTool(resilient, secrets).tool(task.shell().name(), task.shell().description()),
                    new ReportTool(resilient, task.fields(), task.dateShiftDays())
                            .tool(reportName, task.report().description())));

            List<Message> seed = List.of(
                    new Message(Role.system, system),
                    new Message(Role.user, userTemplate.replace(DATA_SLOT, task.dataDir())));

            System.out.println("Model: " + llm.model());

            // Asked after every reply, so the run ends on the reply itself rather than one model call
            // later. Only the report's reply can end it: the shell reads someone else's files, and a
            // flag-shaped string among them is not a result.
            String flag = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, (call, result) -> {
                if (!call.function().name().equals(reportName)) {
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
