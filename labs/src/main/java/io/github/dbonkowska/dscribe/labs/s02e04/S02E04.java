package io.github.dbonkowska.dscribe.labs.s02e04;

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
 * The first lesson whose input has to be searched rather than read. The source sits behind an API
 * that returns metadata pages and fetches bodies by id, and it grows while the run reads it — so the
 * model writes its own queries, reads what it finds, and searches again, and an empty result is not
 * proof that something is absent.
 *
 * <p>The answer is built across submissions rather than given once. Each carries every field, empty
 * where nothing has been found yet, and the hub's reply says which are still wrong; that reply is the
 * only guidance the next round of searching gets.
 *
 * <p>No wait tool. The source moved between two calls made milliseconds apart, so it advances with
 * requests rather than with time, and a wait would wait for nothing. Searching again is how the run
 * gives it a chance to change, and {@link #MAX_ITERATIONS} is what bounds that.
 */
public class S02E04 {

    /**
     * The lesson's own suggestion: the work is finding and copying facts, not reasoning over them,
     * and a loop that may take a dozen or more rounds pays a stronger tier's latency on every one.
     * Not yet earned — re-pinned, with what it cost, once a run gets the flag.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "google/gemini-3-flash-preview";

    /**
     * Counts model round-trips. Generous, as in s02e02: a round is cheap, and searching again is the
     * run's only way to wait for the source to change — a cap that ended the run mid-search would
     * throw away every value already found.
     */
    private static final int MAX_ITERATIONS = 30;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s02e04");
        TaskParams task = lesson.task(TaskParams.class);
        TaskParams.Api api = task.api();

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("api path", api.path());
        settings.put("allowed actions", String.join(", ", api.actions()));

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s02e04",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            ResilientHub resilient = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(List.of(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            // The source's state outlives the process: whatever an earlier run advanced it to, this
            // one would start from, with nothing in its own record explaining why. Reset here, at
            // startup only. A reset in a finally too would buy nothing — nothing else shares this
            // state, and the next run's startup resets it anyway — and it would destroy the end state
            // a failed run leaves behind, which is the evidence for how it failed.
            hub.post("reset", api.path(), Map.of("action", api.resetAction()));

            // Code's call rather than the model's: nothing is being judged yet, and the reply is what
            // the model needs before its first real choice. Asking for it would cost a round.
            String help = hub.post("help", api.path(), Map.of("action", api.helpAction()));

            List<Message> seed = List.of(
                    new Message(Role.system, lesson.prompt("system.md")),
                    new Message(Role.user, lesson.prompt("user.md").formatted(help)));

            CallTool call = new CallTool(hub::post, new CallTool.Spec(api.path(), api.actions()));
            SubmitTool submit = new SubmitTool(resilient, new SubmitTool.Spec(task.fields()));

            // the submit description carries one %s: the formats the check enforces, filled from the
            // same list the check reads so the formats the model is told cannot drift from them
            Toolbox tools = new Toolbox(List.of(
                    call.tool(task.call().name(), task.call().description()),
                    submit.tool(task.submit().name(), task.submit().description().formatted(submit.formats()))));

            AnswerTool<Found> answerTool = new AnswerTool<>(
                    task.answer().name(), task.answer().description(), Found.class);

            System.out.println("Model: " + llm.model());

            Found found = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, answerTool);

            requireItWasActuallySeen(found, task.flagPattern(), submit.responses());

            System.out.println(found.flag());
            transcript.outcome("Reported `" + found.flag() + "`, matched against "
                    + submit.responses().size() + " response(s) the client received.");
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
