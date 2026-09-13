package io.github.dbonkowska.dscribe.labs.s02e02;

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
 * The first lesson to use two models in one run. The planning model cannot see the artefact it
 * has to reason about, so a tool hands that job to a model chosen for it and returns words.
 *
 * <p>Which makes the run's cost two things rather than one: rounds of planning, and moves
 * against a budget the exercise sets. The second is the scarce one, and it is spent on the
 * strength of what the first model was told by the second — so a misreading is paid for in the
 * only currency that does not come back.
 */
public class S02E02 {

    /**
     * The planning half. Started on {@code openai/gpt-5.6-luna}, and that was the weak link: the
     * readings were mostly right, but it lost track of which positions were already correct and
     * started moving ones that were. That is state-tracking across a sequence of moves, which is
     * what a stronger tier buys.
     *
     * <p>One tier up in the same family rather than a {@code -pro} model. The pro tiers reason
     * for a long time per call, and a loop of up to {@link #MAX_ITERATIONS} round-trips pays that
     * on every one of them.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "openai/gpt-5.6-sol";

    /**
     * The looking half. Overridden separately from the planner, through
     * {@code -Dopenrouter.vision.model}, {@code OPENROUTER_VISION_MODEL} and
     * {@code openrouter.vision.model} — which is the whole reason it is a second slot: trying
     * another reader must not retarget the planner, and vice versa.
     *
     * <p>The lesson's own suggestion, and good enough: most positions read correctly. Its readings
     * are not perfectly stable — the same artefact from a fresh reset has come back with one
     * position described two ways — so a misread is possible and the planner should look again
     * rather than trust one reading for a whole plan.
     *
     * <p>{@code google/gemini-3.1-pro-preview} was tried and was too slow to drive the loop: the
     * vision call happens on every look, and a pro tier's latency is paid each time.
     */
    private static final String VISION_MODEL = "google/gemini-3-flash-preview";

    /**
     * Counts model round-trips. Generous because a round here is cheap — the expensive thing is
     * a move, and {@code maxMoves} bounds those separately. A cap that ended the run mid-plan
     * would throw away moves already spent.
     */
    private static final int MAX_ITERATIONS = 30;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);
        LlmClient vision = new LlmClient(labsConfig.llm().vision()).defaultModel(VISION_MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s02e02");
        TaskParams task = lesson.task(TaskParams.class);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("vision model", vision.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max moves", String.valueOf(task.maxMoves()));
        // which of the two ways the target reached the model, so a run that read it badly can be
        // told apart from one that was never shown it
        settings.put("target", task.targetImage() == null ? "described in the prompt" : "shown");
        settings.put("max wait", MAX_WAIT.toString());

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s02e02",
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

            // The artefact's state outlives the process. A run that died halfway left it part-way
            // through, and the next run would start from that without anything in its own record
            // explaining why the artefact does not look the way the prompt describes. Restored
            // here rather than in a finally: this costs a download and nothing from the move
            // budget, where clearing up afterwards would destroy the end state — which, on a run
            // that failed, is the evidence for how it failed.
            hub.downloadBytes(task.dataFile() + "?" + task.resetQuery());

            VisionTool eyes = new VisionTool(
                    vision.withTranscript(transcript.delegated("vision")),
                    hub,
                    lesson.prompt("vision/system.md"),
                    new VisionTool.Spec(task.dataFile(), task.mediaType(), task.targetImage()));

            MoveTool moves = new MoveTool(
                    resilient,
                    new MoveTool.Spec(task.moveKey(), task.positions(), task.maxMoves()));

            Toolbox tools = new Toolbox(List.of(
                    eyes.tool(task.vision().name(), task.vision().description()),
                    moves.tool(task.move().name(), task.move().description())));

            AnswerTool<Found> answerTool = new AnswerTool<>(
                    task.answer().name(), task.answer().description(), Found.class);

            List<Message> seed = List.of(new Message(Role.system, lesson.prompt("system.md")));

            System.out.println("Model: " + llm.model() + " · vision: " + vision.model());

            Found found = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, answerTool);

            requireItWasActuallySeen(found, task.flagPattern(), moves.responses());

            System.out.println(found.flag());
            transcript.outcome("Reported `" + found.flag() + "`, matched against "
                    + moves.responses().size() + " response(s) the client received.");
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
     * produces and the one a pattern cannot see. Both matter more here than in the last lesson:
     * this model is reporting on something it never looked at itself.
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
