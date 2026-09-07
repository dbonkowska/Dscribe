package io.github.dbonkowska.dscribe.labs.s01e02;

import io.github.dbonkowska.dscribe.agent.Agent;
import io.github.dbonkowska.dscribe.agent.AnswerTool;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.Artifacts;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import io.github.dbonkowska.dscribe.tool.Toolbox;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class S01E02 {

    /**
     * The model this lesson's flag was earned on. Pinned because it is the only committed record
     * of that: the transcript header names the model per run, but {@code labs/data} is gitignored,
     * so nothing in version control would otherwise say which model solved this.
     *
     * <p>It took getting to. Eight runs on the repo's old default were rejected, two on
     * {@code google/gemini-3.7-flash} likewise; this one has been right every time. A twelve-step
     * tool chain turns out to be the wrong job for a small model.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it, which is what makes trying another one
     * a flag rather than an edit.
     */
    private static final String MODEL = "openai/gpt-5.6-luna";

    /** Counts model round-trips, not tool calls: one turn may dispatch several. */
    private static final int MAX_ITERATIONS = 12;

    /** Argument shapes the model fills in. Field names are what the hub's API asks for. */
    record SightingQuery(String name, String surname) {}

    record AccessQuery(String name, String surname, int birthYear) {}

    public static void main(String[] args) throws IOException {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s01e02",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            Lesson lesson = Lesson.of(labsConfig, "s01e02");
            TaskParams task = lesson.task(TaskParams.class);

            Artifacts artifacts = Artifacts.of(labsConfig.dataDir(), "s01e02");
            Path locations = hub.fetchData(task.dataFile(), artifacts.file(task.dataFile()));

            // the previous lesson's submitted answer, read as JSON rather than as its Java types
            List<Suspect> suspects =
                    Artifacts.of(labsConfig.dataDir(), "s01e01").readList("answer.json", Suspect.class);

            Toolbox tools = new Toolbox(List.of(
                    hubTool(hub, task.sightings(), SightingQuery.class),
                    hubTool(hub, task.accessLevel(), AccessQuery.class)));

            List<String> vocabulary =
                    vocabulary(artifacts.read(task.dataFile(), JsonNode.class), task.answerVocabulary());

            AnswerTool<Answer> answerTool = new AnswerTool<>(
                    task.answer().name(), task.answer().description(), Answer.class,
                    answerSchema(vocabulary));

            System.out.println("Model: " + llm.model());
            System.out.println("Suspects: " + suspects.size());
            System.out.println("Answer vocabulary: " + vocabulary.size());

            List<Message> seed = List.of(
                    new Message(Role.system, lesson.prompt("system.md")),
                    new Message(Role.user, lesson.prompt("user.md").formatted(
                            roster(suspects), Files.readString(locations, StandardCharsets.UTF_8))));

            Answer answer = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, answerTool);

            System.out.println("Answer: " + answer);
            artifacts.write("answer.json", answer);

            String verified = hub.verify(task.verifyTask(), answer);
            transcript.outcome("Submitted `" + task.answer().name() + "`.\n\n```json\n"
                    + answer + "\n```\n\n`/verify` → " + verified);

            System.out.println(verified);
            System.out.println("Transcript: " + transcript.file());
        }
    }

    /**
     * The generator types {@code powerPlant} as a plain string, which leaves the model free to
     * answer with something the hub rejects outright. Narrowing it to the values the hub's own
     * data file lists makes a wrong one unrepresentable rather than merely discouraged. Same move
     * as s01e01's, and the vocabulary again comes from data rather than from source.
     */
    private static ObjectNode answerSchema(List<String> vocabulary) {
        ObjectNode schema = SchemaUtils.from(Answer.class);
        ArrayNode allowed = SchemaUtils.at(schema, "/properties/powerPlant").putArray("enum");
        vocabulary.forEach(allowed::add);
        return schema;
    }

    /** Where to look is the bundle's to say: the file is the exercise's, and so is its shape. */
    private static List<String> vocabulary(JsonNode file, TaskParams.Vocabulary where) {
        List<String> values = new ArrayList<>();
        file.at(where.pointer()).forEach(entry -> values.add(entry.get(where.key()).stringValue()));
        return values;
    }

    /** Every tool here does the same thing: POST the arguments the model produced to a hub path. */
    private static <A> Tool<A> hubTool(HubClient hub, TaskParams.HubTool spec, Class<A> argumentType) {
        return new Tool<>(
                spec.name(),
                spec.description(),
                argumentType,
                query -> ToolOutput.of(hub.post(spec.name(), spec.path(), query)));
    }

    private static String roster(List<Suspect> suspects) {
        return suspects.stream()
                .map(suspect -> suspect.name() + " " + suspect.surname() + " " + suspect.born())
                .collect(Collectors.joining("\n"));
    }
}