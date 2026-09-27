package io.github.dbonkowska.dscribe.labs.s04e03;

import io.github.dbonkowska.dscribe.agent.Agent;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
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
 * The first lesson where actions cost more than tokens. The environment holds a fixed budget, every
 * unit and every step has a price, and what runs out is not given back.
 *
 * <p>All the planning is the model's. It learns the actions from the endpoint's own help, reads the
 * layout, the prices and what is left of the budget through actions that cost nothing, and commits
 * to the paid ones. The run keeps no price list and no counter of its own: the environment reports
 * both, and a second count beside it would only disagree.
 *
 * <p>Code holds one thing back. The environment can be reset, which restores the budget but wipes
 * the environment's state, so the run sends it once at startup and the tool refuses it to the
 * model. See {@link ActionTool}.
 */
public class S04E03 {

    /**
     * A cheaper tier than s04e01's Sonnet, tried here because a failed attempt costs nothing but
     * tokens: the next run's reset restores the whole budget. A preference, not the last word —
     * {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and {@code openrouter.model} each still
     * win over it.
     */
    private static final String MODEL = "google/gemini-3.8-flash";

    /**
     * Counts model round-trips. Help, a few free reads and a bounded run of paid actions fit well
     * inside it, so this is a backstop rather than the expected exit.
     */
    private static final int MAX_ITERATIONS = 60;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s04e03");
        TaskParams task = lesson.task(TaskParams.class);

        // Read and refused before the transcript opens: a blank prompt would buy a run of a model
        // that was told nothing.
        String system = lesson.prompt("system.md");
        if (system.isBlank()) {
            throw new IllegalStateException("system.md in the s04e03 lesson bundle is blank. Write it before running.");
        }
        Pattern flagPattern = Pattern.compile(task.flagPattern());

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("environment reset", task.resetAction() + ", sent by the run at startup");

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s04e03",
                settings,
                List.of(labsConfig.hub().apiKey(), labsConfig.llm().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            ResilientHub resilient = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(List.of(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            // The reset, once, at startup and before the model sees anything. Sent here rather than
            // through the tool, which refuses it. Nothing resets in a finally — deliberately:
            // nothing else shares this environment, the state a failed run ends in is the evidence
            // for how it failed, and the next run's reset clears it anyway.
            //
            // Sent without retries, and its status checked: every model call after this assumes a
            // clean environment, so a refused reset (the hub answers an unknown action or a wrong
            // task with a 4xx) ends the run here, before a token is spent. A rerun costs nothing.
            HubResponse reset = hub.send(
                    "reset · " + task.resetAction(), task.verifyTask(), Map.of("action", task.resetAction()));
            if (reset.status() / 100 != 2) {
                throw new IllegalStateException(
                        "The startup reset was refused with status " + reset.status() + ": " + reset.body()
                                + ". The environment is not clean, so the run stops before the model"
                                + " is called. Check verifyTask and resetAction in the lesson's"
                                + " task.properties.");
            }

            Toolbox tools = new Toolbox(List.of(
                    new ActionTool(resilient, task.resetAction())
                            .tool(task.action().name(), task.action().description())));

            List<Message> seed = List.of(new Message(Role.system, system));

            System.out.println("Model: " + llm.model());

            // Asked after every reply, so the run ends on the reply itself rather than one model call
            // later. There is one tool, but the name is still checked: a second tool added later
            // should not be able to end the run by quoting a flag.
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
