package io.github.dbonkowska.dscribe.labs.s02e01;

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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The first lesson where the model is not solving the exercise but writing the thing that does.
 * It composes a candidate, one tool call puts that candidate through a full evaluation against an
 * external judge, and it revises from what the judge said — for as many rounds as it takes.
 *
 * <p>Which makes the run's cost rounds rather than tokens or requests, and makes the judge's error
 * messages the only thing worth carrying between them. The rows change every few minutes, so what
 * the model must not do is learn about the rows; the verdict it reads carries none of them.
 */
public class S02E01 {

    /**
     * A starting bet rather than a measured choice, unlike s01e05's.
     *
     * <p>What this lesson pays for is rounds to convergence, and nothing in the repo has been
     * measured on that axis — it is a different question from s01e05's steps-to-solution, because
     * a round here is a whole cycle rather than one request. The lesson's own hint argues for a
     * strong model; the repo's experience argues that the cheaper one kept taking fewer steps.
     * This starts on the workhorse that carried s01e02, s01e04 and s01e05, and gets re-pinned once
     * a run actually earns the result.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it, which is what makes trying another one
     * a flag rather than an edit.
     */
    private static final String MODEL = "openai/gpt-5.6-luna";

    /**
     * Counts model round-trips, so one refinement round is one iteration however many exchanges
     * the cycle underneath it took. Generous on purpose: the lesson says outright that landing a
     * candidate first try is unlikely, and a cap that ends the run mid-refinement throws away
     * everything already spent getting there.
     */
    private static final int MAX_ITERATIONS = 30;

    /**
     * The ceiling on any single wait. Not a limit on the run, which is deliberately unbounded:
     * this keeps a misread header to a wrong-length pause instead of a hang.
     */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s02e01");
        TaskParams task = lesson.task(TaskParams.class);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("token cap", task.prompt().cap() + " (margin " + task.prompt().margin() + ")");

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s02e01",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            ResilientHub resilient = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(task.limits().resetHeaders(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            Rendering rendering = new Rendering(
                    task.prompt().idPlaceholder(),
                    task.prompt().descriptionPlaceholder(),
                    new TokenBudget(task.prompt().cap(), task.prompt().margin()));

            CycleTool cycles = new CycleTool(hub, resilient, rendering, new CycleTool.Spec(
                    task.data().file(),
                    task.data().idColumn(),
                    task.data().descriptionColumn(),
                    task.resetPrompt(),
                    Pattern.compile(task.failurePattern()),
                    Pattern.compile(task.flagPattern())));

            // Each cycle clears the counter after itself, which covers everything except a killed
            // process — and a Ctrl-C during a long wait is the expected way to end a run sitting
            // too long. Without this, the next run's first cycle starts on budget it never spent
            // and is refused for a reason nothing in its transcript explains. One submission.
            resilient.call("reset · startup", new Submission(task.resetPrompt()));

            // Parsed once here and thrown away, so that a column name which does not match the
            // file fails before the loop exists. Inside a tool call the same failure becomes a
            // tool result, and the model — handed the file's real column names by the error —
            // rewrites its prompt around them, which the placeholder check then rejects. That
            // pair is unsatisfiable and cost two killed runs before this guard existed.
            // A download, and nothing from the submission budget.
            List<Item> rows = Rows.parse(
                    hub.downloadData(task.data().file()),
                    task.data().idColumn(),
                    task.data().descriptionColumn());

            if (rows.isEmpty()) {
                throw new IllegalStateException(
                        "The downloaded file " + task.data().file() + " has headers but no rows,"
                                + " so there is nothing for a candidate to be judged against.");
            }

            // the description carries one %d: the size a rendered prompt is actually held to.
            // Filled from Rendering so the number the model is told and the number enforced are
            // the same one — the first run wasted its opening round discovering it by refusal.
            Toolbox tools = new Toolbox(List.of(cycles.tool(
                    task.cycle().name(),
                    task.cycle().description().formatted(rendering.effectiveCap()))));

            AnswerTool<Found> answerTool = new AnswerTool<>(
                    task.answer().name(), task.answer().description(), Found.class);

            List<Message> seed = List.of(new Message(Role.system, lesson.prompt("system.md")));

            System.out.println("Model: " + llm.model());

            Found found = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, answerTool);

            requireItWasActuallySeen(found, task.flagPattern(), cycles.responses());

            System.out.println(found.flag());
            transcript.outcome("Reported `" + found.flag() + "`, matched against "
                    + cycles.responses().size() + " response(s) the client received.");
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
