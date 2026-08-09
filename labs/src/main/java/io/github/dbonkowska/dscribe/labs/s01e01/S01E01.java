package io.github.dbonkowska.dscribe.labs.s01e01;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.labs.util.SchemaUtils;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.llm.ResponseFormat;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class S01E01 {

    /** Where the generated schema keeps the per-tag constraint. */
    private static final String TAG_ITEMS = "/properties/results/items/properties/tags/items";

    record Row(Person person, String job) {}

    public static void main(String[] args) throws IOException {
        LabsConfig labsConfig = LabsConfig.load();
        HubClient hub = new HubClient(labsConfig.hub());
        LlmClient llm = new LlmClient(labsConfig.llm()).withModel("google/gemma-4-26b-a4b-it:free");

        Lesson lesson = Lesson.of(labsConfig, "s01e01");
        TaskParams task = lesson.task(TaskParams.class);

        Path data = hub.fetchFromHub("s01e01", task.dataFile());

        List<Row> candidates = parseCsv(data).stream()
                .filter(row -> matchesHardCriteria(row, task))
                .toList();

        System.out.println("Model: " + llm.model());
        System.out.println("Candidates after CSV filtering: " + candidates.size());

        Map<Integer, List<String>> tagsById = classify(llm, candidates, task, lesson.prompt("system.md"));

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

        System.out.println(hub.verify(task.verifyTask(), answer));
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