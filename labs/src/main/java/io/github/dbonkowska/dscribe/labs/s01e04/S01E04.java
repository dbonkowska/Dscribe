package io.github.dbonkowska.dscribe.labs.s01e04;

import io.github.dbonkowska.dscribe.agent.Agent;
import io.github.dbonkowska.dscribe.agent.AnswerTool;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.tool.Toolbox;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class S01E04 {

    /**
     * The model this lesson's flag was earned on, pinned for the same reason as the earlier
     * lessons: {@code labs/data} is gitignored, so nothing else in version control records it.
     *
     * <p>Chosen strong rather than cheap. Part of the documentation is a diagram, and a weak
     * vision model misreading one fails in a way that looks like bad reasoning rather than bad
     * perception — which is the most expensive kind of run to debug. Drop to something cheaper
     * only after a run has succeeded once.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "anthropic/claude-sonnet-5";

    /**
     * Counts model round-trips, not tool calls. The documentation is a tree of roughly a dozen
     * files reached by following links, and nothing tells the run in advance how many of them it
     * needs — so this is deliberately looser than the earlier lessons'.
     */
    private static final int MAX_ITERATIONS = 25;

    /** A placeholder the template still carries: {@code {name}}, nothing filled it. */
    private static final Pattern UNFILLED = Pattern.compile("\\{[a-zA-Z]+}");

    /** The shape {@code /verify} expects: the whole document as one string. */
    record Submission(String declaration) {}

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s01e04",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            Lesson lesson = Lesson.of(labsConfig, "s01e04");
            TaskParams task = lesson.task(TaskParams.class);

            Toolbox tools = new Toolbox(List.of(
                    FetchTool.of(task.fetch().name(), task.fetch().description())));

            AnswerTool<Declaration> answerTool = new AnswerTool<>(
                    task.answer().name(), task.answer().description(), Declaration.class);

            List<Message> seed = List.of(
                    new Message(Role.system, lesson.prompt("system.md")),
                    new Message(Role.user, fill(lesson.prompt("user.md"), task.briefing())));

            System.out.println("Model: " + llm.model());
            System.out.println("Docs: " + task.briefing().docsUrl());

            Declaration declaration = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, answerTool);

            String document = fill(lesson.prompt("declaration.template"), declaration)
                    .replace("{date}", LocalDate.now().toString());

            requireEverySlotFilled(document);
            reportBriefingDrift(declaration, task.briefing());

            System.out.println(document);

            String verified = hub.verify(task.verifyTask(), new Submission(document));
            transcript.outcome("Submitted `" + task.answer().name() + "`.\n\n```\n"
                    + document + "\n```\n\n`/verify` → " + verified);

            System.out.println(verified);
            System.out.println("Transcript: " + transcript.file());
        }
    }

    /**
     * Replaces every {@code {component}} in {@code template} with that component's value.
     *
     * <p>By name rather than by position. The document has eleven slots and the briefing seven,
     * all of them strings — {@code String.formatted} would take them in an order nothing checks,
     * and two swapped arguments produce a perfectly well-formed wrong document that the hub
     * rejects without saying why.
     *
     * <p>Local to this lesson on purpose. It moves into {@code Lesson} when a second lesson needs
     * it, not before.
     */
    private static String fill(String template, Record values) {
        String filled = template;
        for (RecordComponent component : values.getClass().getRecordComponents()) {
            filled = filled.replace("{" + component.getName() + "}", read(component, values));
        }
        return filled;
    }

    private static String read(RecordComponent component, Record values) {
        try {
            return String.valueOf(component.getAccessor().invoke(values));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + component.getName(), e);
        }
    }

    /**
     * A slot nothing filled would be submitted verbatim, and the rejection would name nothing
     * useful. Cheaper to fail here than to spend a {@code /verify} call finding out.
     */
    private static void requireEverySlotFilled(String document) {
        if (UNFILLED.matcher(document).find()) {
            throw new IllegalStateException(
                    "The declaration still has unfilled slots:\n" + document);
        }
    }

    /**
     * The model re-types five values it was given rather than deriving them, so a slip is
     * possible and invisible in the finished document.
     *
     * <p>Reported rather than thrown: the briefing states a mass in kilograms and the form may
     * want it written another way, so an exact mismatch is a reason to look at the document
     * before it goes, not a reason to throw away a run that reached this point.
     */
    private static void reportBriefingDrift(Declaration declaration, TaskParams.Briefing briefing) {
        Map<String, String> given = new LinkedHashMap<>();
        given.put("sender", briefing.sender());
        given.put("origin", briefing.origin());
        given.put("destination", briefing.destination());
        given.put("mass", briefing.mass());
        given.put("contents", briefing.contents());

        Map<String, String> answered = new LinkedHashMap<>();
        answered.put("sender", declaration.sender());
        answered.put("origin", declaration.origin());
        answered.put("destination", declaration.destination());
        answered.put("mass", declaration.mass());
        answered.put("contents", declaration.contents());

        given.forEach((field, expected) -> {
            String actual = answered.get(field);
            if (!expected.equals(actual)) {
                System.out.println(
                        "! " + field + ": briefing said \"" + expected + "\", declaration says \""
                                + actual + "\"");
            }
        });
    }
}
