package io.github.dbonkowska.dscribe.labs.s03e05;

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
 * The first lesson whose agent does not know its tools when it starts. It is given a search, a way
 * to call what the search finds, and a way to submit; everything else — what the tools are, what
 * they say, and what to do with it — is the model's to work out.
 *
 * <p>Nothing here plans, parses or checks the answer. The rules the exercise states up front are in
 * the system prompt, the rest arrive as tool replies, and a rejected answer comes back from verify
 * as a result the model can act on. Code owns only where the hub key may be sent — see
 * {@link DiscoveryTools}.
 */
public class S03E05 {

    /**
     * The model carries the whole plan here, discovery and arithmetic included, so this starts from
     * a tier that has earned flags on multi-step runs (s03e03). A preference, not the last word —
     * {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and {@code openrouter.model} each still
     * win over it, so a cheaper tier is tried without an edit.
     */
    private static final String MODEL = "anthropic/claude-sonnet-4-6";

    /**
     * Counts model round-trips. Discovery costs several before anything is submitted, and a rejected
     * answer is expected to cost a few more, so this is a backstop rather than the expected exit.
     */
    private static final int MAX_ITERATIONS = 40;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s03e05");
        TaskParams task = lesson.task(TaskParams.class);

        // Read and refused before the transcript opens: a blank prompt would buy a run of a model
        // that was told nothing.
        String system = required(lesson, "system.md");
        String user = required(lesson, "user.md");
        Pattern flagPattern = Pattern.compile(task.flagPattern());

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("search path", task.searchPath());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s03e05",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            ResilientHub verify = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(List.of(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            DiscoveryTools discovery = new DiscoveryTools(hub::post, verify, task);
            Toolbox tools = new Toolbox(List.of(discovery.search(), discovery.call(), discovery.submit()));

            List<Message> seed = List.of(
                    new Message(Role.system, system),
                    new Message(Role.user, user));

            System.out.println("Model: " + llm.model());

            // Only a submit reply can end the run. A found tool's reply is the hub's text too, and a
            // flag-shaped string in one of those is not a result anyone earned.
            String submitName = task.submit().name();
            String flag = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, (call, result) -> {
                if (!call.function().name().equals(submitName)) {
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

    private static String required(Lesson lesson, String fileName) {
        String prompt = lesson.prompt(fileName);
        if (prompt.isBlank()) {
            throw new IllegalStateException(
                    fileName + " in the s03e05 lesson bundle is blank. Write it before running.");
        }
        return prompt;
    }
}
