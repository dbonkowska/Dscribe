package io.github.dbonkowska.dscribe.labs.s01e05;

import io.github.dbonkowska.dscribe.agent.Agent;
import io.github.dbonkowska.dscribe.agent.AnswerTool;
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
import java.util.regex.Pattern;

/**
 * The first lesson whose difficulty is the transport rather than the reasoning. The API fails on
 * purpose, rations requests, and documents itself only when asked — so the run has to survive
 * being refused, wait when it is told to, and read its way to what the API can even do.
 *
 * <p>That last part is why this is genuinely an agent loop rather than a script. The actions and
 * their parameters exist only in a response, so nothing here can name them: they cannot be a
 * constant, a record, or a key in the lesson bundle. The model reads them and decides.
 */
public class S01E05 {

    /**
     * Chosen for this lesson's actual constraint rather than for capability. The budget is spent
     * in requests, not tokens, so what decides a run is steps to solution — and a model that
     * explores more thoroughly spends exactly the resource being rationed. Reaching for a stronger
     * one is the trap here.
     *
     * <p>This is the only model the repo has measured on that axis. In s01e04 it finished in five
     * round-trips where a model ten times the price took far more, reading fewer documents rather
     * than struggling with them, and it has carried s01e02 as well.
     *
     * <p>It earned the flag, in five model round-trips: no invented action or parameter names, no
     * asking the API to describe itself twice, and every refusal absorbed underneath it without
     * the model being told a thing. The bet held — what this lesson pays for is steps, and the
     * cheaper model took fewer of them.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it, which is what makes trying another one
     * a flag rather than an edit.
     */
    private static final String MODEL = "openai/gpt-5.6-luna";

    /**
     * Counts model round-trips, not hub attempts. A retried 503 costs nothing here — it is spent
     * inside one tool call — so this only has to cover reading the documentation and walking the
     * sequence it describes.
     */
    private static final int MAX_ITERATIONS = 20;

    /**
     * The ceiling on any single wait. Not a limit on the run, which is deliberately unbounded:
     * this is what keeps a misread header to a wrong-length pause instead of a hang, since the
     * value's unit is guessed from its magnitude.
     */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s01e05");
        TaskParams task = lesson.task(TaskParams.class);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s01e05",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            ResilientHub resilient = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(
                            task.limits().resetHeaders(), task.limits().remainingHeaders(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            CallTool calls = new CallTool(resilient);
            Toolbox tools = new Toolbox(List.of(
                    calls.tool(task.call().name(), task.call().description())));

            AnswerTool<Found> answerTool = new AnswerTool<>(
                    task.answer().name(), task.answer().description(), Found.class);

            List<Message> seed = List.of(new Message(Role.system, lesson.prompt("system.md")));

            System.out.println("Model: " + llm.model());

            Found found = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, answerTool);

            requireItWasActuallySeen(found, task.flagPattern(), calls.responses());

            System.out.println(found.flag());
            transcript.outcome("Reported `" + found.flag() + "`, matched against "
                    + calls.responses().size() + " response(s) the client received.");
            System.out.println("Transcript: " + transcript.file());
        }
    }

    /**
     * The model reports the result rather than the hub confirming it, so nothing else stands
     * between a hallucinated answer and a run that declares itself finished.
     *
     * <p>Two checks, because they fail differently. The pattern catches a result of the wrong
     * shape — a paraphrase, a truncation, a description of one. The substring search catches a
     * well-shaped result that no response ever contained, which is the failure a plausible model
     * produces and the one a pattern cannot see.
     */
    private static void requireItWasActuallySeen(Found found, String pattern, List<String> responses) {
        if (found.flag() == null || !Pattern.compile(pattern).matcher(found.flag()).find()) {
            throw new IllegalStateException(
                    "Reported result does not match " + pattern + ": " + found.flag());
        }
        if (responses.stream().noneMatch(response -> response.contains(found.flag()))) {
            throw new IllegalStateException(
                    "Reported result appears in none of the " + responses.size()
                            + " response(s) the hub actually returned: " + found.flag());
        }
    }
}
