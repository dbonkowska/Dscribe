package io.github.dbonkowska.dscribe.labs.s04e02;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.llm.ResponseFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A service window of a few dozen seconds, a queue that answers in any order, and one model call
 * inside it. The hub queues every job and closes the window a fixed time after it opens; the
 * slowest job alone takes more than half of it, so the model gets what is left — one structured
 * call, no loop.
 *
 * <p>Nothing here names the task. The fields a point has come from the hub's own help reply, read
 * before the window opens, and the model's answer is checked against a schema built from them;
 * every protocol name and closed vocabulary is in the lesson's {@code task.properties}. See
 * {@link PointSchema} for what the model answers, {@link Collector} for the waiting and {@link Echo}
 * for how a signature finds its point.
 */
public class S04E02 {

    /**
     * Chosen for latency, not reasoning: measured 1.0–1.3s on a realistic input (~6k tokens in),
     * the steadiest of the fast models tried, against a budget of about eleven seconds. A
     * preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and
     * {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "google/gemini-3.5-flash-lite";

    /**
     * Its reasoning cannot be switched off; at {@code minimal} it used none when measured. Left
     * unset, the provider's default decides how long the call thinks — inside a window that closes.
     */
    private static final String REASONING_EFFORT = "minimal";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A {@code {name}} placeholder in {@code user.md}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL).reasoningEffort(REASONING_EFFORT);

        Lesson lesson = Lesson.of(labsConfig, "s04e02");
        TaskParams task = lesson.task(TaskParams.class);
        TaskParams.Actions actions = task.actions();
        TaskParams.Protocol protocol = task.protocol();
        Pattern flagPattern = Pattern.compile(task.flagPattern());

        // Checked before the transcript opens: a prompt that cannot be rendered is found here, not
        // inside the window after the slowest job has already been waited for.
        String system = lesson.prompt("system.md");
        if (system.isBlank()) {
            throw new IllegalStateException("system.md in the s04e02 lesson bundle is blank. Write it before running.");
        }
        List<String> slots = new ArrayList<>();
        slots.add(task.documentation());
        slots.addAll(task.jobs());
        String userTemplate = lesson.prompt("user.md");
        requireEachSlotOnce(userTemplate, slots);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("reasoning effort", REASONING_EFFORT);
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("poll interval", task.pollIntervalMs() + " ms");
        settings.put("safety margin", task.safetyMarginMs() + " ms");

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s04e02",
                settings,
                List.of(labsConfig.hub().apiKey(), labsConfig.llm().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);
            InstantSource clock = InstantSource.system();
            // Each call is one attempt: a retry's wait would come out of the window.
            Call call = (label, answer) -> hub.send(label, task.verifyTask(), answer);

            // Before the window: what a point is made of, and the documentation.
            JsonNode help = accepted(call.send(actions.help(), Map.of(protocol.actionField(), actions.help())));
            PointSchema schema = PointSchema.from(
                    help, protocol.signFields(), protocol.configFields(), protocol.signatureField(),
                    task.slotFields(), task.enums());
            transcript.note("point schema", "fields " + schema.fields() + "\n\n" + schema.schema().toPrettyString());
            JsonNode documentation = accepted(call.send(task.documentation(), Map.of(
                    protocol.actionField(), actions.get(), protocol.paramField(), task.documentation())));

            JsonNode started = accepted(call.send(actions.start(), Map.of(protocol.actionField(), actions.start())));
            Instant opened = clock.instant();
            int timeout = started.path(protocol.timeoutField()).asInt(0);
            if (timeout <= 0) {
                throw new IllegalStateException("The start reply names no window length: " + started);
            }
            // The hub's own figure, taken from its reply rather than written here.
            Instant deadline = opened.plusSeconds(timeout).minusMillis(task.safetyMarginMs());
            Elapsed elapsed = new Elapsed(clock, opened, transcript);

            for (String job : task.jobs()) {
                accepted(call.send(job, Map.of(protocol.actionField(), actions.get(), protocol.paramField(), job)));
            }
            elapsed.note("queued " + String.join(", ", task.jobs()));

            Collector collector = new Collector(
                    () -> call.send(actions.getResult(), Map.of(protocol.actionField(), actions.getResult())),
                    protocol.codeField(),
                    task.pendingCode(),
                    Duration.ofMillis(task.pollIntervalMs()),
                    clock,
                    Sleeper.real());

            Collected<String> data = collector.collect(
                    Set.copyOf(task.jobs()),
                    node -> Optional.of(node.path(protocol.sourceField()).asString("")).filter(s -> !s.isEmpty()),
                    deadline);
            elapsed.note("collected " + String.join(", ", data.results().keySet()), data.unexpected());

            Map<String, String> filled = new LinkedHashMap<>();
            filled.put(task.documentation(), documentation.toString());
            task.jobs().forEach(job -> filled.put(job, data.results().get(job).toString()));
            List<Message> messages = List.of(
                    new Message(Role.system, system),
                    new Message(Role.user, fill(userTemplate, filled)));
            JsonNode answer = recorded.sendStructured(
                    messages, ResponseFormat.jsonSchema("points", schema.schema()), JsonNode.class);
            List<Map<String, String>> points = schema.validate(answer);
            elapsed.note("model answered " + points.size() + " points\n\n" + points.stream()
                    .map(Map::toString)
                    .collect(Collectors.joining("\n")));

            // Keyed by slot: unique per point (validated), the batch key, and the signing call's label,
            // so a deadline message names the same thing the transcript does.
            Map<String, Map<String, Object>> signing = new LinkedHashMap<>();
            for (Map<String, String> point : points) {
                Map<String, Object> sent = new LinkedHashMap<>();
                schema.signFields().forEach(field -> sent.put(field, PointSchema.sendable(point.get(field))));
                signing.put(schema.slot(point), sent);

                Map<String, Object> request = new LinkedHashMap<>();
                request.put(protocol.actionField(), actions.sign());
                request.putAll(sent);
                accepted(call.send(actions.sign() + " · " + schema.slot(point), request));
            }
            // A signature says which point it is for only by echoing its values, so it is matched to
            // the point whose sent values it echoes.
            Collected<String> codes = collector.collect(
                    signing.keySet(),
                    node -> node.path(protocol.sourceField()).asString("").equals(actions.sign())
                            ? signing.entrySet().stream()
                                    .filter(sent -> Echo.matches(sent.getValue(), node.path(protocol.echoField())))
                                    .map(Map.Entry::getKey)
                                    .findFirst()
                            : Optional.empty(),
                    deadline);
            elapsed.note("signed " + codes.results().size() + " points", codes.unexpected());

            Map<String, Object> batch = new LinkedHashMap<>();
            for (Map<String, String> point : points) {
                String slot = schema.slot(point);
                // Refused here, the earliest point it exists: sent on as "", it would come back as the
                // config's rejection, naming the batch rather than the reply that lacked it.
                String signature = codes.results().get(slot).path(protocol.signatureField()).asString("");
                if (signature.isBlank()) {
                    throw new IllegalStateException(
                            "The signing result for " + slot + " carries no " + protocol.signatureField()
                                    + ": " + codes.results().get(slot));
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                schema.entryFields().forEach(field -> entry.put(field, PointSchema.sendable(point.get(field))));
                entry.put(protocol.signatureField(), signature);
                batch.put(slot, entry);
            }
            accepted(call.send(actions.config(), Map.of(
                    protocol.actionField(), actions.config(), protocol.batchField(), batch)));
            elapsed.note("configured");

            HubResponse done = call.send(actions.done(), Map.of(protocol.actionField(), actions.done()));
            elapsed.note("done");

            Matcher found = flagPattern.matcher(done.body());
            if (found.find()) {
                System.out.println(found.group());
                transcript.outcome("Earned `" + found.group() + "` after " + elapsed.millis() + " ms.");
            } else {
                System.out.println("Rejected: " + done.body());
                transcript.outcome("Rejected after " + elapsed.millis() + " ms: " + done.body());
            }
            System.out.println("Transcript: " + transcript.file());
        }
    }

    /**
     * By name rather than by position, as in s01e04: four JSON documents are all strings, and two
     * swapped would give the model a well-formed wrong prompt that nothing downstream notices.
     *
     * <p>In one pass over the template. Replacing name by name would scan text already inserted, and
     * a hub document that happened to contain {@code {jobname}} would get a job's JSON spliced into
     * it — a collision the startup check cannot see, because it only reads the template.
     */
    private static String fill(String template, Map<String, String> values) {
        return PLACEHOLDER.matcher(template).replaceAll(match ->
                Matcher.quoteReplacement(values.getOrDefault(match.group(1), match.group())));
    }

    /**
     * Each slot exactly once. A missing one hands the model a prompt without that document; a
     * doubled one pays for the same document twice, inside the window.
     */
    private static void requireEachSlotOnce(String template, List<String> slots) {
        for (String slot : slots) {
            String placeholder = "{" + slot + "}";
            int count = template.split(Pattern.quote(placeholder), -1).length - 1;
            if (count != 1) {
                throw new IllegalStateException(
                        "user.md has " + placeholder + " " + count + " times; it needs it exactly once, for"
                                + " each of " + slots.stream().map(s -> "{" + s + "}").toList() + ".");
            }
        }
    }

    /** A reply the run can go on from: a 200 with a JSON body. Anything else ends the run here. */
    private static JsonNode accepted(HubResponse response) {
        if (response.status() != 200) {
            throw new IllegalStateException("Hub refused [" + response.status() + "]: " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    @FunctionalInterface
    private interface Call {
        HubResponse send(String label, Object answer);
    }

    /**
     * Time since the window opened, written to the transcript after every step, so a run that misses
     * the deadline reads as where the time went.
     */
    private record Elapsed(InstantSource clock, Instant opened, RunTranscript transcript) {

        long millis() {
            return Duration.between(opened, clock.instant()).toMillis();
        }

        void note(String step) {
            note(step, List.of());
        }

        void note(String step, List<JsonNode> unexpected) {
            String body = step;
            if (!unexpected.isEmpty()) {
                body += "\n\nset aside, expected by nothing:\n\n" + unexpected.stream()
                        .map(JsonNode::toString)
                        .collect(Collectors.joining("\n"));
            }
            transcript.note("t+" + millis() + " ms", body);
        }
    }
}
