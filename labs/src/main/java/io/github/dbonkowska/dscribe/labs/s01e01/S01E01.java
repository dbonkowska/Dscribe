package io.github.dbonkowska.dscribe.labs.s01e01;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.Artifacts;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.llm.ResponseFormat;
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class S01E01 {

    /**
     * The model this lesson's flag was earned on. Pinned because it is the only committed record
     * of that: the transcript header names the model per run, but {@code labs/data} is gitignored,
     * so nothing in version control would otherwise say which model solved this.
     *
     * <p>Free tier, and it was enough — one-shot structured output against a strict schema asks
     * far less of a model than the tool chain in s01e02, which had to escalate off the free tier
     * to finish at all.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it, which is what makes trying another one
     * a flag rather than an edit.
     */
    private static final String MODEL = "google/gemma-4-26b-a4b-it:free";

    /** Where the generated schema keeps the per-tag constraint. */
    private static final String TAG_ITEMS = "/properties/results/items/properties/tags/items";

    record Row(Person person, String job) {}

    public static void main(String[] args) throws IOException {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s01e01",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            Lesson lesson = Lesson.of(labsConfig, "s01e01");
            TaskParams task = lesson.task(TaskParams.class);

            Artifacts artifacts = Artifacts.of(labsConfig.dataDir(), "s01e01");
            Path data = hub.fetchData(task.dataFile(), artifacts.file(task.dataFile()));

            List<Row> candidates = parseCsv(data).stream()
                    .filter(row -> matchesHardCriteria(row, task))
                    .toList();

            System.out.println("Model: " + llm.model());
            System.out.println("Candidates after CSV filtering: " + candidates.size());

            Map<Integer, List<String>> tagsById =
                    classify(recorded, candidates, task, lesson.prompt("system.md"));

            List<Person> answer = new ArrayList<>();
            for (int id = 0; id < candidates.size(); id++) {
                List<String> tags = tagsById.get(id);
                if (tags == null) {
                    System.out.println("WARN: model returned no entry for id " + id);
                    continue;
                }
                if (tags.contains(task.selectTag())) {
                    answer.add(candidates.get(id).person().withTags(tags));
                }
            }

            System.out.println("Selected: " + answer.size());
            answer.forEach(p -> System.out.println("  " + p.name() + " " + p.surname() + " " + p.tags()));

            // the next lesson consumes this; a runner that only prints its answer loses it
            artifacts.write("answer.json", answer);

            String verified = hub.verify(task.verifyTask(), answer);
            transcript.outcome("Submitted " + answer.size() + " people.\n\n`/verify` → " + verified);

            System.out.println(verified);
            System.out.println("Transcript: " + transcript.file());
        }
    }

    static boolean matchesHardCriteria(Row row, TaskParams task) {
        Person person = row.person();
        int age = task.referenceYear() - person.born();

        return task.gender().equals(person.gender())
                && age >= task.minAge()
                && age <= task.maxAge()
                && task.city().equals(person.city());
    }

    private static Map<Integer, List<String>> classify(
            LlmClient llm, List<Row> rows, TaskParams task, String systemTemplate) {

        TagResult result = llm.sendStructured(
                buildMessages(rows, task, systemTemplate),
                ResponseFormat.jsonSchema("tag_result", tagSchema(task.tags())),
                TagResult.class
        );

        return result.results().stream()
                .collect(Collectors.toMap(TagResult.Entry::id, TagResult.Entry::tags, (a, b) -> a));
    }

    /**
     * The generated schema types {@code tags} as an array of plain strings; this narrows it
     * to the lesson's vocabulary. Done here rather than with a Java enum so the vocabulary
     * stays in the lesson bundle — strict mode enforces it either way.
     */
    static ObjectNode tagSchema(List<String> vocabulary) {
        ObjectNode schema = SchemaUtils.from(TagResult.class);
        ArrayNode allowed = SchemaUtils.at(schema, TAG_ITEMS).putArray("enum");
        vocabulary.forEach(allowed::add);
        return schema;
    }

    /** {@code systemTemplate} carries a single {@code %s} placeholder for the tag vocabulary. */
    private static List<Message> buildMessages(List<Row> rows, TaskParams task, String systemTemplate) {
        String system = systemTemplate.formatted(String.join(", ", task.tags()));

        String user = IntStream.range(0, rows.size())
                .mapToObj(i -> i + ". " + rows.get(i).job())
                .collect(Collectors.joining("\n\n"));

        return List.of(
                new Message(Role.system, system),
                new Message(Role.user, user)
        );
    }

    static List<Row> parseCsv(Path path) throws IOException {
        List<Row> rows = new ArrayList<>();

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             var parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                rows.add(new Row(Person.from(record), record.get("job")));
            }
        }

        return rows;
    }
}