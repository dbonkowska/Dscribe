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
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Toolbox;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class S01E04 {

    /**
     * The flag has been earned on this model and on {@code anthropic/claude-sonnet-5}, recorded
     * here because {@code labs/data} is gitignored and nothing else in version control would say
     * so.
     *
     * <p>Sonnet went first, chosen strong rather than cheap: part of the documentation is a
     * diagram, and a weak vision model misreading one fails in a way that looks like bad
     * reasoning rather than bad perception. It earned the flag on its second attempt, having
     * first written a paragraph into a field the briefing said to leave empty.
     *
     * <p>This one is pinned because the step down turned out to cost nothing. At 0.20/1.20
     * against Sonnet's 2.00/10.00 per 1M tokens it is a tenth of the price, and it finished in
     * five round-trips where Sonnet took far more — a cheaper model reading fewer documents, not
     * a cheaper model struggling. The diagram was never the hard part.
     *
     * <p>What made the difference was not the model. Two of Sonnet's three runs were rejected for
     * the *shape* of a value rather than its substance — one field answered as a prose label
     * where a code was wanted, another written into at all — and both were afterwards taken out
     * of the model's hands entirely. Luna's first run under those constraints passed.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "openai/gpt-5.6-luna";

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

    /**
     * The slots the runner fills rather than the model — component names matching their
     * placeholders, so the same {@link #fill} pass handles them.
     */
    record Fixed(String date, String remarks) {}

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
                    task.answer().name(), task.answer().description(), Declaration.class,
                    answerSchema(task.form().categories()));

            List<Message> seed = List.of(
                    new Message(Role.system, lesson.prompt("system.md")),
                    new Message(Role.user, fill(lesson.prompt("user.md"), task.briefing())));

            System.out.println("Model: " + llm.model());
            System.out.println("Docs: " + task.briefing().docsUrl());

            Declaration declaration = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, answerTool);

            String document = fill(
                    fill(lesson.prompt("declaration.template"), declaration),
                    new Fixed(LocalDate.now().toString(), task.form().remarks()));

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
     * The generator types {@code category} as a plain string, which leaves the model free to
     * answer with something the hub cannot parse — and it did, answering a prose label where a
     * code was expected, for a rejection that reported the field as *missing* rather than
     * malformed. Narrowing it to the vocabulary the bundle supplies makes that unrepresentable
     * rather than merely unlikely. Same move as s01e01's and s01e02's, and the vocabulary again
     * comes from the bundle rather than from source.
     */
    private static ObjectNode answerSchema(List<String> categories) {
        ObjectNode schema = SchemaUtils.from(Declaration.class);
        ArrayNode allowed = SchemaUtils.at(schema, "/properties/category").putArray("enum");
        categories.forEach(allowed::add);
        return schema;
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
