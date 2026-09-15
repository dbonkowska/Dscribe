package io.github.dbonkowska.dscribe.labs.s02e03;

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
import io.github.dbonkowska.dscribe.labs.tokens.TokenBudget;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.tool.Toolbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first lesson whose input does not fit in front of the model. Code reduces it to a map of
 * distinct entries and makes the first submission itself; the model only ever sees the map, what
 * the hub replied, and whatever raw lines it asks to look at — and its whole job is choosing
 * which entries belong in a text too small to hold them all.
 *
 * <p>Which makes the run's cost rounds of feedback rather than tokens of input. The source is read
 * once per run and never enters the conversation; what grows is the list of replies, and each one
 * is the only guidance the next attempt gets.
 */
public class S02E03 {

    /**
     * The workhorse of s02e01, rather than the stronger tier s02e02 needed: that one lost track of
     * which moves it had already made, and here there is no such state to track — every submission
     * restates the whole selection, and the reply to it is in the conversation.
     *
     * <p>The flag was first earned without it: the attempt code builds was accepted, and the model was
     * never called. The loop was then exercised on purpose, from a first attempt chosen to fall
     * short, and this model earned the flag in four iterations and two submissions — zooming several
     * windows in one turn before submitting, and overshooting the budget once, which was refused
     * locally with nothing sent. The run's specifics live with the lesson, not here.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "openai/gpt-5.6-luna";

    /**
     * Counts model round-trips. A round here is a zoom or a submission, and the lesson expects
     * several submissions before the hub accepts one — so this is sized for looking
     * around between them, and a cap that ended the run mid-refinement would waste every reply
     * already earned.
     */
    private static final int MAX_ITERATIONS = 20;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    /**
     * How far either side of a moment one zoom may look. Lines run a few a minute, so this is on the
     * order of a hundred and a half lines at most — a real look around, and nowhere near enough to
     * put the source back into the conversation one call at a time.
     */
    private static final int MAX_ZOOM_MINUTES = 30;

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s02e03");
        TaskParams task = lesson.task(TaskParams.class);
        TokenBudget budget = task.budget().tokens();

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("max zoom minutes", String.valueOf(MAX_ZOOM_MINUTES));
        settings.put("token budget", budget.cap() + " (margin " + budget.margin() + ")");

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s02e03",
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

            // Read fresh every run, never from a cached copy: the source is dated relative to the
            // day it is fetched, so yesterday's copy submits a date the hub no longer
            // expects. As bytes rather than as text so the record carries its size and not its body
            // — the whole point of this lesson is that the body is too large to keep anywhere.
            EventMap map = EventMap.parse(
                    new String(hub.downloadBytes(task.dataFile()), StandardCharsets.UTF_8),
                    Pattern.compile(task.linePattern()));
            System.out.println("Read " + map.lines().size() + " lines, " + map.events().size() + " entries");

            SubmitTool submit = new SubmitTool(
                    resilient, map, new SubmitTool.Spec(task.answerKey(), task.lineFormat(), budget));

            List<String> firstIds = map.idsWithSeverity(task.firstAttempt());
            if (firstIds.isEmpty()) {
                // Reachable: a source with none of those severities parses fine. Sent anyway, it
                // would be refused as an empty selection with a message meant for the model, which
                // is not yet in the room to read it.
                throw new IllegalStateException(
                        "No entries at the severities in firstAttempt " + task.firstAttempt()
                                + " — check them against what the source actually logs.");
            }

            // The first attempt is code's. What it contains is decided by a fixed rule, so there is
            // nothing for a model to judge yet; making it here saves a round, and hands the model
            // what it actually needs to start from — the hub's first reply.
            SubmitTool.Attempt first = submit.submit(firstIds);

            Matcher earlyFlag = Pattern.compile(task.flagPattern()).matcher(first.response());
            if (earlyFlag.find()) {
                report(new Found(earlyFlag.group()), task.flagPattern(), submit, transcript);
                return;
            }

            String firstAttempt = String.join(", ", firstIds)
                    + "\n\n" + first.tokens() + " tokens of the " + first.effectiveCap() + " that may be sent.";

            List<Message> seed = List.of(
                    new Message(Role.system, lesson.prompt("system.md")),
                    new Message(Role.user, lesson.prompt("user.md")
                            .formatted(map.renderMap(), firstAttempt, first.response())));

            ZoomTool zoom = new ZoomTool(map, MAX_ZOOM_MINUTES);

            // each description carries one %d: the limit its tool actually enforces, filled from
            // the same value the check reads so the number the model is told cannot drift from it
            Toolbox tools = new Toolbox(List.of(
                    zoom.tool(task.zoom().name(), task.zoom().description().formatted(MAX_ZOOM_MINUTES)),
                    submit.tool(task.submit().name(), task.submit().description().formatted(budget.effectiveCap()))));

            AnswerTool<Found> answerTool = new AnswerTool<>(
                    task.answer().name(), task.answer().description(), Found.class);

            System.out.println("Model: " + llm.model());

            Found found = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, answerTool);

            report(found, task.flagPattern(), submit, transcript);
        }
    }

    private static void report(Found found, String pattern, SubmitTool submit, RunTranscript transcript) {
        requireItWasActuallySeen(found, pattern, submit.responses());

        System.out.println(found.flag());
        transcript.outcome("Reported `" + found.flag() + "`, matched against "
                + submit.responses().size() + " response(s) the client received.");
        System.out.println("Transcript: " + transcript.file());
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
