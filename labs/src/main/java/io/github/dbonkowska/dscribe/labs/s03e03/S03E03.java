package io.github.dbonkowska.dscribe.labs.s03e03;

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
 * The first lesson whose run ends on what a tool returned rather than on anything the model says.
 * The environment only advances when it is sent a command, and its reply carries both the new state
 * and, eventually, the result — so the agent asks an observer about every reply and stops on the
 * first one that says the run is over.
 *
 * <p>The model decides each command, reading the reply exactly as the environment wrote it. Nothing
 * here knows how the environment moves or what a good command is: that is the system prompt's,
 * and it lives with the exercise rather than in this repository.
 *
 * <p>The command is one of a closed set, so the tool's schema carries the set and a wrong command is
 * unrepresentable.
 *
 * <p>Nothing resets the environment. What the exercise's reset command does is not known yet, so the
 * runner neither sends it nor offers it, and the transcript's settings say the state is inherited.
 */
public class S03E03 {

    /**
     * The tier that earned the flag on its first attempt, every time it was run. Cheaper tiers were
     * tried and did not, and the comparison, with its costs and attempt counts, is kept with the
     * lesson's notes rather than here, where it would go stale with the next rung.
     *
     * <p>What carries over from it: the price is input tokens, nearly all of it the environment's
     * reply replayed to the model on every round, so a cheaper rate is not a cheaper flag when each
     * failed attempt spends one of a limited stock. Each rung is tried by stepping down from a run
     * that worked, and a run is one attempt, so a model is judged on several of them.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and
     * {@code openrouter.model} each still win over it, so another tier is tried without an edit.
     */
    private static final String MODEL = "anthropic/claude-sonnet-4-6";

    /**
     * Counts model round-trips. A backstop rather than the expected exit: a run that has to pause a
     * lot needs room. The observer is what is meant to end the run, on the result or on a failure.
     */
    private static final int MAX_ITERATIONS = 40;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s03e03");
        TaskParams task = lesson.task(TaskParams.class);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("commands offered", String.join(", ", task.commands()));
        settings.put("environment reset", "none: state is inherited from the previous run, check that run first");

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s03e03",
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

            // No reset, here or in a finally. The environment's state outlives the process, so a run
            // can start from wherever an earlier one left it, and the settings block above says so
            // for whoever reads a confusing transcript. Add one at startup only, once its effect is
            // known: a finally would destroy the state a failed run ends in, which is the evidence
            // for how it failed.
            List<Message> seed = List.of(
                    new Message(Role.system, lesson.prompt("system.md")),
                    new Message(Role.user, lesson.prompt("user.md")));

            CommandTool commandTool = new CommandTool(
                    resilient, new CommandTool.Spec(task.commandKey(), task.commands()));
            Toolbox tools = new Toolbox(List.of(
                    commandTool.tool(task.command().name(), task.command().description())));

            System.out.println("Model: " + llm.model());

            Pattern flagPattern = Pattern.compile(task.flagPattern());
            Pattern crashPattern = Pattern.compile(task.crashPattern());

            // Asked after every reply, so the run ends on the reply itself rather than one model
            // call later. The result is checked first: where a reply could be read as both, the
            // result is the one worth keeping.
            Outcome outcome = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, (call, result) -> {
                Matcher flag = flagPattern.matcher(result.text());
                if (flag.find()) {
                    return Optional.of(new Outcome.Flag(flag.group()));
                }
                if (crashPattern.matcher(result.text()).find()) {
                    return Optional.of(new Outcome.Crash(result.text()));
                }
                return Optional.empty();
            });

            switch (outcome) {
                case Outcome.Flag flag -> {
                    System.out.println(flag.text());
                    transcript.outcome("Earned `" + flag.text() + "`.");
                }
                case Outcome.Crash crash -> {
                    System.out.println("The run ended in a crash: " + crash.reply());
                    transcript.outcome("Ended in a crash. The reply that ended it: " + crash.reply());
                }
            }
            System.out.println("Transcript: " + transcript.file());
        }
    }
}
