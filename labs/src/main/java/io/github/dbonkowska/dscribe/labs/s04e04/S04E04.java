package io.github.dbonkowska.dscribe.labs.s04e04;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.Artifacts;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.llm.ResponseFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Unstructured notes turned into a small knowledge base: one file per entity, linked to the
 * others by markdown links, on a filesystem the hub holds.
 *
 * <p>The model does only what takes reading prose, in one structured call. The one note with a
 * fixed format is parsed by code, and its places become the enum every place the model names is
 * narrowed to. Paths, links and spelling are built here, checked against the limits the hub's help
 * states, and sent as one batch that starts with a reset — so a re-run starts clean. See
 * {@link TradeLog}, {@link Extraction} and {@link FileTree}.
 *
 * <p>Nothing here names the task: file names, directory names, action names and the prompts are
 * all in the lesson bundle.
 */
public class S04E04 {

    private static final String LESSON = "s04e04";

    /**
     * One structured call over a few kilobytes of notes, so a fast tier is enough. A preference,
     * not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and
     * {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "google/gemini-3.8-flash";

    private static final String UNPACKED = "notes";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A {@code {name}} placeholder in {@code user.md}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");

    /** The slots {@code user.md} fills: the two prose notes and the two closed lists. */
    private static final List<String> SLOTS = List.of("needs", "calls", "cities", "goods");

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, LESSON);
        TaskParams task = lesson.task(TaskParams.class);
        TaskParams.Actions actions = task.actions();
        Pattern flagPattern = Pattern.compile(task.flagPattern());
        Artifacts artifacts = Artifacts.of(labsConfig.dataDir(), LESSON);

        // Checked before anything is fetched: a prompt that cannot be rendered is found here, not
        // after the archive, the help call and the parse.
        String system = lesson.prompt("system.md");
        if (system.isBlank()) {
            throw new IllegalStateException("system.md in the s04e04 lesson bundle is blank. Write it before running.");
        }
        String userTemplate = lesson.prompt("user.md");
        requireEachSlotOnce(userTemplate, SLOTS);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                LESSON,
                settings,
                List.of(labsConfig.hub().apiKey(), labsConfig.llm().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            // fetched once and unpacked once; re-running touches no network for these
            hub.fetch(task.archive().url(), artifacts.file(task.archive().file()));
            artifacts.unzip(task.archive().file(), UNPACKED);
            Path notes = artifacts.file(UNPACKED);
            String needs = read(notes.resolve(task.notes().needs()));
            String calls = read(notes.resolve(task.notes().calls()));
            TradeLog tradeLog = TradeLog.parse(
                    read(notes.resolve(task.notes().transactions())).lines().toList(),
                    task.transactions().separator());
            transcript.note("trade log", "cities " + tradeLog.cities() + "\n\ngoods " + tradeLog.goods());

            // Before the model: the rules a name has to meet, in the hub's own words.
            Limits limits = Limits.from(accepted(hub.send(
                    actions.help(), task.verifyTask(), Map.of("action", actions.help()))));
            transcript.note("limits", limits.toString());

            Map<String, String> filled = new LinkedHashMap<>();
            filled.put("needs", needs);
            filled.put("calls", calls);
            filled.put("cities", String.join("\n", tradeLog.cities()));
            filled.put("goods", String.join("\n", tradeLog.goods()));
            List<Message> messages = List.of(
                    new Message(Role.system, system),
                    new Message(Role.user, fill(userTemplate, filled)));
            Extraction extraction = recorded.sendStructured(
                    messages,
                    ResponseFormat.jsonSchema("extraction", Extraction.schema(tradeLog.cities(), tradeLog.goods())),
                    Extraction.class);

            List<Map<String, Object>> batch = FileTree.build(extraction, tradeLog, task.dirs(), actions);
            transcript.note("batch", batch.stream().map(Map::toString).collect(Collectors.joining("\n")));

            // One refusal naming everything, before the hub sees any of it: a rejected done says
            // far less than this list does.
            List<String> problems = new ArrayList<>(extraction.problems(tradeLog.goods()));
            problems.addAll(FileTree.check(batch, limits, actions));
            if (!problems.isEmpty()) {
                String listed = problems.stream().map(p -> "- " + p).collect(Collectors.joining("\n"));
                transcript.outcome("Refused before sending:\n\n" + listed);
                throw new IllegalStateException("The batch was not sent:\n" + listed);
            }

            accepted(hub.send("batch", task.verifyTask(), batch));

            HubResponse done = hub.send(actions.done(), task.verifyTask(), Map.of("action", actions.done()));
            Matcher found = flagPattern.matcher(done.body());
            if (found.find()) {
                System.out.println(found.group());
                transcript.outcome("Earned `" + found.group() + "`.");
            } else {
                System.out.println("Rejected: " + done.body());
                transcript.outcome("Rejected: " + done.body());
            }
            System.out.println("Transcript: " + transcript.file());
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /**
     * In one pass over the template, so a note that happens to contain {@code {cities}} does not
     * get the list spliced into it.
     */
    private static String fill(String template, Map<String, String> values) {
        return PLACEHOLDER.matcher(template).replaceAll(match ->
                Matcher.quoteReplacement(values.getOrDefault(match.group(1), match.group())));
    }

    /**
     * Each slot exactly once. A missing one hands the model a prompt without that note; a doubled
     * one pays for the same note twice.
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
}
