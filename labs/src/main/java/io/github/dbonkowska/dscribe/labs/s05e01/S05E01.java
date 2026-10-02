package io.github.dbonkowska.dscribe.labs.s05e01;

import io.github.dbonkowska.dscribe.conversation.ContentPart;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.Artifacts;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.labs.s05e01.Payload.Dropped;
import io.github.dbonkowska.dscribe.labs.s05e01.Payload.Route;
import io.github.dbonkowska.dscribe.labs.s05e01.Payload.Routed;
import io.github.dbonkowska.dscribe.labs.s05e01.TaskParams.Field;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.llm.ResponseFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Material pulled one payload at a time, sorted by code, and turned into a report by a
 * model cascade: the cheapest tier first, the next only while a field is still missing.
 *
 * <p>Every payload becomes text before a model reads it. Text and structured files are read as they
 * are; audio is transcribed by a speech model; images are described by a vision model. One
 * extraction model reads the accumulated text after each tier, and code decides from its answer
 * whether the next, dearer tier runs at all. See {@link Payload} and {@link Report}.
 *
 * <p>Nothing here names the task: actions, codes, field names and prompts are in the lesson bundle.
 */
public class S05E01 {

    private static final String LESSON = "s05e01";

    /**
     * One structured call per tier over a few thousand tokens of text, so a fast tier is enough. A
     * preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and
     * {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "google/gemini-3.8-flash";

    /**
     * Whisper-class, billed by duration at $0.000008 a second: the probe's clip costs a fraction of
     * a cent. Overridden only by {@code openrouter.speech.model}, so a chat override cannot send
     * audio to a model that cannot hear it.
     */
    private static final String SPEECH_MODEL = "openai/whisper-large-v3";

    /** Reads what an image shows. Overridden only by {@code openrouter.vision.model}. */
    private static final String VISION_MODEL = "google/gemini-3.8-flash";

    /** Generous: a session that never sends the end code stops here instead of listening forever. */
    private static final int MAX_LISTENS = 200;

    private static final String ACTION = TaskParams.ACTION_KEY;
    private static final String CORPUS_SLOT = "{corpus}";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);
        LlmClient speech = new LlmClient(labsConfig.llm().speech()).defaultModel(SPEECH_MODEL);
        LlmClient vision = new LlmClient(labsConfig.llm().vision()).defaultModel(VISION_MODEL);

        Lesson lesson = Lesson.of(labsConfig, LESSON);
        TaskParams task = lesson.task(TaskParams.class);
        Pattern flagPattern = Pattern.compile(task.flagPattern());

        // Everything refusable is refused here, before the session is opened or a token spent.
        String system = nonBlank(lesson.prompt("system.md"), "system.md");
        String userTemplate = nonBlank(lesson.prompt("user.md"), "user.md");
        String visionPrompt = nonBlank(lesson.prompt("vision/system.md"), "vision/system.md");
        int slots = userTemplate.split(Pattern.quote(CORPUS_SLOT), -1).length - 1;
        if (slots != 1) {
            throw new IllegalStateException(
                    "user.md has " + CORPUS_SLOT + " " + slots + " times; it needs it exactly once.");
        }

        Path runDir = Artifacts.of(labsConfig.dataDir(), LESSON).file(RUN.format(LocalDateTime.now()));

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("speech model", speech.model());
        settings.put("vision model", vision.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max listens", String.valueOf(MAX_LISTENS));
        settings.put("payloads", runDir.toString());

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                LESSON,
                settings,
                List.of(labsConfig.hub().apiKey(), labsConfig.llm().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            List<Routed> routed = collect(hub, task, runDir, transcript);

            List<String> corpus = new ArrayList<>();
            List<Field> fields = task.fields();
            Report report = Report.from(fields, Map.of());

            // Tier 1: text as it came, structured files decoded.
            for (Routed item : only(routed, Route.TEXT)) {
                corpus.add(block(item, "") + item.text());
            }
            report = report.mergedWith(extract(recorded, system, userTemplate, corpus, fields));
            transcript.note("tier 1 · text", tierSummary(only(routed, Route.TEXT).size(), report));

            // Tier 2: audio, transcribed. Only while something is still missing.
            List<Routed> audio = only(routed, Route.SPEECH);
            if (!report.missing().isEmpty() && !audio.isEmpty()) {
                LlmClient listening = speech.withTranscript(transcript.delegated("speech"));
                for (Routed item : audio) {
                    String heard = listening.transcribe(item.bytes(), audioFormat(item.kind())).text();
                    corpus.add(block(item, ", transcribed") + heard);
                }
                report = report.mergedWith(extract(recorded, system, userTemplate, corpus, fields));
                transcript.note("tier 2 · speech", tierSummary(audio.size(), report));
            }

            // Tier 3: images, described. The dearest, so last.
            List<Routed> images = only(routed, Route.VISION);
            if (!report.missing().isEmpty() && !images.isEmpty()) {
                LlmClient looking = vision.withTranscript(transcript.delegated("vision"));
                for (Routed item : images) {
                    List<Message> look = List.of(
                            new Message(Role.system, visionPrompt),
                            new Message(Role.user, List.of(ContentPart.image(item.kind(), item.bytes())), null, null));
                    // nulls rather than an empty list: this asks for prose, not a tool call
                    String seen = looking.send(look, null, null).message().text();
                    corpus.add(block(item, ", described") + seen);
                }
                report = report.mergedWith(extract(recorded, system, userTemplate, corpus, fields));
                transcript.note("tier 3 · vision", tierSummary(images.size(), report));
            }

            if (!report.missing().isEmpty()) {
                transcript.outcome("Nothing sent: after every tier, still missing " + report.missing() + ".");
                throw new IllegalStateException("Nothing was sent: still missing " + report.missing() + ".");
            }

            // formatted by code before anything leaves: a value its format cannot read stops here
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put(ACTION, task.actions().transmit());
            answer.putAll(report.answer());
            transcript.note("report", answer.toString());

            HubResponse reply = hub.send(task.actions().transmit(), task.verifyTask(), answer);
            Matcher found = flagPattern.matcher(reply.body());
            if (found.find()) {
                System.out.println(found.group());
                transcript.outcome("Earned `" + found.group() + "`.");
            } else {
                System.out.println("Rejected: " + reply.body());
                transcript.outcome("Rejected: " + reply.body());
            }
            System.out.println("Transcript: " + transcript.file());
        }
    }

    /**
     * Opens the session and listens until the hub says there is nothing more, keeping every raw
     * reply and every decoded file on disk. Raw replies and files live in separate directories, so a
     * decoded {@code .json} can never overwrite the reply it came in — the probe lost one that way.
     */
    private static List<Routed> collect(HubClient hub, TaskParams task, Path runDir, RunTranscript transcript) {
        accepted(hub.send(task.actions().start(), task.verifyTask(), Map.of(ACTION, task.actions().start())));

        List<Routed> routed = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int noisy = 0;
        boolean ended = false;

        for (int n = 1; n <= MAX_LISTENS; n++) {
            HubResponse response = hub.send(
                    task.actions().listen() + " " + n, task.verifyTask(), Map.of(ACTION, task.actions().listen()));
            write(runDir.resolve("raw").resolve(String.format("%03d.json", n)), response.body().getBytes(StandardCharsets.UTF_8));
            JsonNode reply = accepted(response);

            int code = reply.path("code").asInt();
            if (code == task.endCode()) {
                ended = true;
                break;
            }
            // Any other code is the hub saying something this run was not built for. Dropping it and
            // listening again would end, at the cap, on a message blaming endCode for the hub's words.
            if (code != task.payloadCode()) {
                transcript.outcome("Stopped on listen " + n + ": unexpected code " + code + ": " + response.body());
                throw new IllegalStateException(
                        "Listen " + n + " came back with code " + code + ", neither a payload ("
                                + task.payloadCode() + ") nor the end (" + task.endCode() + "): " + response.body());
            }

            switch (Payload.classify(n, reply)) {
                case Routed item -> {
                    routed.add(item);
                    if (item.bytes() != null) {
                        write(runDir.resolve("files").resolve(String.format("%03d.%s", n, extension(item.kind()))), item.bytes());
                    }
                    if (item.route() == Route.TEXT && "transcription".equals(item.kind())
                            && containsAny(item.text(), task.noiseMarkers())) {
                        noisy++;
                    }
                }
                case Dropped item -> dropped.add("#" + n + " " + item.kind() + ": " + item.reason());
            }
        }

        if (!ended) {
            throw new IllegalStateException(
                    "The hub never sent end code " + task.endCode() + " in " + MAX_LISTENS + " listens. Check"
                            + " endCode in the lesson's task.properties.");
        }

        Map<Route, Long> byRoute = routed.stream().collect(Collectors.groupingBy(Routed::route, Collectors.counting()));
        transcript.note("collected", "routed " + byRoute
                + "\n\ntranscriptions with a noise marker (kept): " + noisy
                + "\n\ndropped: " + (dropped.isEmpty() ? "none" : "\n\n- " + String.join("\n- ", dropped)));
        return routed;
    }

    /** One structured call over everything gathered so far. */
    private static Report extract(
            LlmClient llm, String system, String userTemplate, List<String> corpus, List<Field> fields) {

        List<Message> messages = List.of(
                new Message(Role.system, system),
                new Message(Role.user, userTemplate.replace(CORPUS_SLOT, String.join("\n\n", corpus))));
        Map<?, ?> reply = llm.sendStructured(
                messages, ResponseFormat.jsonSchema("report", Report.schema(fields)), Map.class);
        Map<String, Object> values = new LinkedHashMap<>();
        reply.forEach((key, value) -> values.put(String.valueOf(key), value));
        return Report.from(fields, values);
    }

    private static List<Routed> only(List<Routed> routed, Route route) {
        return routed.stream().filter(item -> item.route() == route).toList();
    }

    /** The label each item carries in the corpus, so the model can tell its sources apart. */
    private static String block(Routed item, String how) {
        return String.format("[#%03d %s%s]%n", item.number(), item.kind(), how);
    }

    private static String tierSummary(int items, Report report) {
        return items + " item(s) added\n\nstill missing: "
                + (report.missing().isEmpty() ? "nothing" : report.missing());
    }

    /** The speech endpoint wants the container's name; {@code audio/mpeg} is an MP3. */
    private static String audioFormat(String mediaType) {
        String subtype = mediaType.substring(mediaType.indexOf('/') + 1);
        return "mpeg".equals(subtype) ? "mp3" : subtype;
    }

    private static String extension(String mediaType) {
        return mediaType.substring(mediaType.indexOf('/') + 1).replaceAll("[^A-Za-z0-9.-]", "_");
    }

    private static boolean containsAny(String text, List<String> markers) {
        String lower = text.toLowerCase(Locale.ROOT);
        return markers.stream().anyMatch(marker -> lower.contains(marker.toLowerCase(Locale.ROOT)));
    }

    private static String nonBlank(String prompt, String name) {
        if (prompt.isBlank()) {
            throw new IllegalStateException(name + " in the s05e01 lesson bundle is blank. Write it before running.");
        }
        return prompt;
    }

    /** A reply the run can go on from: a 200 with a JSON body. Anything else ends the run here. */
    private static JsonNode accepted(HubResponse response) {
        if (response.status() != 200) {
            throw new IllegalStateException("Hub refused [" + response.status() + "]: " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
