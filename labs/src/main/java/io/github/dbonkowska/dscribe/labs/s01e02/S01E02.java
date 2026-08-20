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
     * Pinned so a re-run reproduces this lesson rather than following whatever
     * {@code application.properties} happens to say. It also beats {@code -Dopenrouter.model} —
     * {@code withModel} is applied after the config resolves — so comparing models means
     * changing this line.
     */
    private static final String MODEL = "openai/gpt-5.6-luna";

    /** Counts model round-trips, not tool calls: one turn may dispatch several. */
    private static final int MAX_ITERATIONS = 12;

    /** Argument shapes the model fills in. Field names are what the hub's API asks for. */
    record SightingQuery(String name, String surname) {}

    record AccessQuery(String name, String surname, int birthYear) {}

    public static void main(String[] args) throws IOException {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).withModel(MODEL);

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

            List<String> codes = plantCodes(artifacts.read(task.dataFile(), JsonNode.class));

            AnswerTool<Answer> answerTool = new AnswerTool<>(
                    task.answer().name(), task.answer().description(), Answer.class, answerSchema(codes));

            System.out.println("Model: " + llm.model());
            System.out.println("Suspects: " + suspects.size());
            System.out.println("Plant codes offered: " + codes.size());

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
     * The generated schema types {@code powerPlant} as a plain string, which lets the model answer
     * with a plant's name — the hub rejects that outright. Narrowing it to the codes the hub's own
     * file lists makes a wrong shape unrepresentable rather than merely discouraged. Same move as
     * s01e01's tag vocabulary, and the vocabulary again comes from data rather than from source.
     */
    private static ObjectNode answerSchema(List<String> codes) {
        ObjectNode schema = SchemaUtils.from(Answer.class);
        ArrayNode allowed = SchemaUtils.at(schema, "/properties/powerPlant").putArray("enum");
        codes.forEach(allowed::add);
        return schema;
    }

    private static List<String> plantCodes(JsonNode file) {
        List<String> codes = new ArrayList<>();
        file.at("/power_plants").forEach(plant -> codes.add(plant.get("code").stringValue()));
        return codes;
    }

    /** Every tool here does the same thing: POST the arguments the model produced to a hub path. */
    private static <A> Tool<A> hubTool(HubClient hub, TaskParams.HubTool spec, Class<A> argumentType) {
        return new Tool<>(
                spec.name(),
                spec.description(),
                argumentType,
                query -> hub.post(spec.name(), spec.path(), query));
    }

    private static String roster(List<Suspect> suspects) {
        return suspects.stream()
                .map(suspect -> suspect.name() + " " + suspect.surname() + " " + suspect.born())
                .collect(Collectors.joining("\n"));
    }
}