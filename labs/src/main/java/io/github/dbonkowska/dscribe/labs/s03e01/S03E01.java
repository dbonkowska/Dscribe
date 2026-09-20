package io.github.dbonkowska.dscribe.labs.s03e01;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A pipeline, not an agent loop.
 *
 * <p>No step's output changes which input the next step reads: fetch, unpack, rule, deduplicate,
 * judge, score, submit. The model is a classifier here rather than an actor, so there is nothing
 * for {@code Agent} to do, and inventing a loop to keep the framework in the picture would only
 * add ways to stop early by mistake. <strong>The absence is deliberate — not an oversight to be
 * corrected.</strong>
 *
 * <p>The saving in this lesson is not triage. The rule pass decides a small fraction of the corpus
 * and leaves almost all of it; what makes the run affordable is collapsing those records onto the
 * handful of distinct phrases they are assembled from, so the model is asked once per phrase
 * instead of once per record. Measured on the first successful run: two model calls, about ten
 * thousand tokens, under two cents. Asking once per remaining record would have repeated the
 * system prompt some ten thousand times, which on the same prices comes to roughly two orders of
 * magnitude more — and that choice is made here, before anything is sent.
 *
 * <p>Output is the expensive half, by more than the token counts suggest: on that run completion
 * cost seven times what the prompt did at a similar token count. Worth knowing before adding a
 * field to the answer schema, and the reason the verdicts carry an index and nothing else.
 */
public class S03E01 {

    private static final String LESSON = "s03e01";

    /**
     * The cheapest model that can hold the judgement, which is the whole point of this lesson.
     * Every input is a short phrase and every answer is one word from a closed vocabulary — the
     * easiest thing a small model does. Escalate on the measurement in the transcript's usage
     * table, not on suspicion.
     */
    private static final String MODEL = "google/gemini-3-flash-preview";

    /** Where the archive is unpacked, beside it. A local layout choice, not the exercise's. */
    private static final String UNPACKED = "records";

    /** What the next lesson may read. Written from the reply the hub actually returned. */
    record Answer(String flag, int submitted, String unit) {}

    public static void main(String[] args) throws IOException {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, LESSON);
        TaskParams task = lesson.task(TaskParams.class);
        Artifacts artifacts = Artifacts.of(labsConfig.dataDir(), LESSON);

        String judgePrompt = lesson.prompt("system.md");
        List<Label> labels = lesson.jsonList("eval.json", Label.class);

        Composition composition = new Composition(
                task.fields().clauseSeparator(),
                task.stance().problem(),
                task.judge().agreementThreshold());

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("channels", String.valueOf(task.channels().size()));
        settings.put("batch size", String.valueOf(task.judge().batchSize()));
        settings.put("sample size", String.valueOf(task.judge().sampleSize()));
        settings.put("agreement threshold", String.valueOf(task.judge().agreementThreshold()));
        settings.put("labels", String.valueOf(labels.size()));

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                LESSON,
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            // fetched once and unpacked once; re-running costs nothing and touches no network
            hub.fetch(task.archive().url(), artifacts.file(task.archive().file()));
            artifacts.unzip(task.archive().file(), UNPACKED);

            List<Reading> readings = readAll(artifacts, task);
            System.out.println("Records: " + readings.size());

            // --- the deterministic pass -------------------------------------------------------
            Rules rules = new Rules(task.channels(), task.fields().typeSeparator());
            Set<String> flagged = new TreeSet<>();
            Map<Rules.Kind, Integer> violationsByKind = new LinkedHashMap<>();
            List<Reading> clean = new ArrayList<>();
            int byRule = 0;

            for (Reading reading : readings) {
                List<Rules.Violation> violations = rules.violations(reading);
                if (violations.isEmpty()) {
                    clean.add(reading);
                    continue;
                }
                flagged.add(reading.id());
                byRule++;
                // counted separately from the records above, and never summed with them: one
                // record can break two rules, and the two numbers coincide only by accident of
                // this corpus. A count believed rather than taken is the thing that expires.
                violations.forEach(v -> violationsByKind.merge(v.kind(), 1, Integer::sum));
            }

            System.out.println("Flagged by rule: " + byRule + " records · violations "
                    + violationsByKind);
            System.out.println("Clean, note to read: " + clean.size());

            // to the file, not only the console: a rule firing on nothing — or on everything — is
            // visible here and nowhere else, and the hub's reply will not mention it
            transcript.note("rule pass", "| measure | value |\n|---|---|\n"
                    + "| records read | " + readings.size() + " |\n"
                    + "| flagged, by rule | " + byRule + " |\n"
                    + "| violations by kind | " + violationsByKind + " |\n"
                    + "| clean, note to read | " + clean.size() + " |");

            // --- deduplication ----------------------------------------------------------------
            // only clean records reach here. A note agreeing with bad measurements adds no id the
            // measurements have not already produced, so its record is already answered for.
            List<String> notes = distinct(clean.stream().map(Reading::notes).toList());
            List<String> parts = distinct(notes.stream().flatMap(n -> composition.parts(n).stream()).toList());

            System.out.println("Distinct notes: " + notes.size() + " · distinct parts: " + parts.size());

            ResponseFormat format = ResponseFormat.jsonSchema(
                    "verdicts", verdictSchema(task.stance().vocabulary()));
            Judge judge = new Judge(
                    inputs -> recorded.sendStructured(
                            ask(judgePrompt, inputs), format, Verdicts.class),
                    task.judge().batchSize());

            // the cheap half first: every distinct part, once
            Map<String, String> partStances = judge.stances(parts);

            // --- the gate ---------------------------------------------------------------------
            // before the expensive half, so a model reading the corpus badly costs one pass rather
            // than two. This is the only outside opinion the run gets before the hub's.
            Evaluation.Passed passed = Evaluation.check(labels, partStances);
            System.out.println("Evaluation: " + passed.checked() + " labels, all agreeing");

            transcript.note("gate", passed.checked()
                    + " hand-labelled phrases, all agreeing with the model. Nothing has been"
                    + " submitted yet.");

            // --- is the cheap unit allowed to stand in? ---------------------------------------
            // the first N in file order rather than a random N: reproducibility was preferred to
            // representativeness, so a re-run batches identically and a disagreement can be
            // chased. Worth revisiting if the corpus ever groups its notes by anything.
            List<String> sample = notes.subList(0, Math.min(task.judge().sampleSize(), notes.size()));
            Map<String, String> noteStances = new LinkedHashMap<>(judge.stances(sample));

            Composition.Agreement agreement = composition.over(sample, noteStances, partStances);
            System.out.println("Composition: " + agreement.rate() + " over " + sample.size()
                    + " sampled notes → judging by " + agreement.unit()
                    + (agreement.disagreed().isEmpty() ? "" : " · disagreed: " + agreement.disagreed()));

            transcript.note("composition", "| measure | value |\n|---|---|\n"
                    + "| distinct notes | " + notes.size() + " |\n"
                    + "| distinct parts | " + parts.size() + " |\n"
                    + "| sampled notes | " + sample.size() + " |\n"
                    + "| agreement | " + agreement.rate() + " |\n"
                    + "| threshold | " + task.judge().agreementThreshold() + " |\n"
                    + "| judging by | " + agreement.unit() + " |\n"
                    + (agreement.disagreed().isEmpty()
                            ? ""
                            : "\nDisagreed:\n\n- " + String.join("\n- ", agreement.disagreed()) + "\n"));

            if (agreement.unit() == Composition.Unit.NOTE) {
                // the fallback is the expensive path by an order of magnitude, and it is taken
                // rather than avoided: a cheaper answer that is wrong earns nothing
                List<String> remaining = notes.stream().filter(n -> !noteStances.containsKey(n)).toList();
                System.out.println("Falling back to whole notes: " + remaining.size() + " more to judge");
                noteStances.putAll(judge.stances(remaining));
            }

            // --- fan out ----------------------------------------------------------------------
            int byNote = 0;
            for (Reading reading : clean) {
                boolean claims = agreement.unit() == Composition.Unit.CLAUSE
                        ? composition.composedClaimsProblem(reading.notes(), partStances)
                        : composition.judgedAsProblem(reading.notes(), noteStances);
                if (claims) {
                    flagged.add(reading.id());
                    byNote++;
                }
            }

            System.out.println("Flagged by note: " + byNote + " · total: " + flagged.size());

            // --- one submission ---------------------------------------------------------------
            String response = hub.verify(
                    task.verifyTask(), Map.of(task.answerKey(), List.copyOf(flagged)));

            Matcher flagMatch = Pattern.compile(task.flagPattern()).matcher(response);
            if (!flagMatch.find()) {
                throw new IllegalStateException(
                        "The reply carried nothing matching " + task.flagPattern() + " for "
                                + flagged.size() + " submitted ids. There is no second attempt that"
                                + " learns anything: the hub names nothing it rejected.");
            }
            String flag = flagMatch.group();

            artifacts.write("answer.json",
                    new Answer(flag, flagged.size(), agreement.unit().name()));

            System.out.println(flag);
            transcript.outcome("Submitted " + flagged.size() + " ids — " + byRule + " by rule, "
                    + byNote + " by note (violations by kind: " + violationsByKind + "), judged by "
                    + agreement.unit() + " at agreement " + agreement.rate()
                    + ", and earned `" + flag + "`.");
            System.out.println("Transcript: " + transcript.file());
        }
    }

    /**
     * One call's messages: the bundle's instructions, and the inputs numbered for the answer to
     * refer back to.
     *
     * <p>The numbering is written here rather than asked for, and it is what the index check in
     * {@code Judge} compares against.
     */
    private static List<Message> ask(String system, List<String> inputs) {
        StringBuilder listed = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            listed.append(i).append('\t').append(inputs.get(i)).append('\n');
        }
        return List.of(
                new Message(Role.system, system),
                new Message(Role.user, listed.toString()));
    }

    /**
     * The answer schema, with the stance narrowed to the vocabulary the bundle supplies.
     *
     * <p>A closed vocabulary becomes a schema enum from configuration, never a Java enum: the
     * constraint survives into the request, the constant does not enter the repository.
     */
    private static ObjectNode verdictSchema(List<String> vocabulary) {
        ObjectNode schema = SchemaUtils.from(Verdicts.class);
        ArrayNode allowed = SchemaUtils
                .at(schema, "/properties/verdicts/items/properties/stance")
                .putArray("enum");
        vocabulary.forEach(allowed::add);
        return schema;
    }

    /** First occurrence wins, and the order is stable, so a re-run batches identically. */
    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    /**
     * Every record in the unpacked archive, read through the field names the bundle supplies.
     *
     * <p>A file whose name does not match the id pattern is skipped — archives carry incidental
     * entries — but a run that matched <em>nothing</em> refuses rather than submitting an empty
     * set, because a pattern that fits no file looks exactly like a corpus with no anomalies.
     */
    private static List<Reading> readAll(Artifacts artifacts, TaskParams task) throws IOException {
        Path dir = artifacts.file(UNPACKED);
        Pattern idPattern = Pattern.compile(task.archive().idPattern());
        List<Reading> readings = new ArrayList<>();
        String anyName = null;

        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                anyName = name;

                Matcher matcher = idPattern.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }

                JsonNode node = artifacts.read(UNPACKED + "/" + name, JsonNode.class);
                Map<String, Double> values = new LinkedHashMap<>();
                for (TaskParams.Channel channel : task.channels()) {
                    JsonNode value = node.get(channel.field());
                    if (value != null) {
                        values.put(channel.field(), value.doubleValue());
                    }
                }

                readings.add(new Reading(
                        matcher.group(1),
                        text(node, task.fields().type(), name),
                        values,
                        text(node, task.fields().notes(), name)));
            }
        }

        if (readings.isEmpty()) {
            throw new IllegalStateException(
                    "No file in " + dir + " matched archive.idPattern (" + task.archive().idPattern()
                            + "); one of them is named '" + anyName + "'. An empty corpus submits an"
                            + " empty set, which the hub cannot distinguish from a wrong one.");
        }
        return readings;
    }

    private static String text(JsonNode node, String field, String file) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new UncheckedIOException(new IOException(
                    file + " carries no '" + field + "'. Check fields.* in the lesson's"
                            + " task.properties against a record in the archive."));
        }
        return value.stringValue();
    }
}
