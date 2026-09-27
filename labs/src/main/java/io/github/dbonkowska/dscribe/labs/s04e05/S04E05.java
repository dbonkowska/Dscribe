package io.github.dbonkowska.dscribe.labs.s04e05;

import io.github.dbonkowska.dscribe.agent.Agent;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.Artifacts;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.tool.Toolbox;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A model exploring a database it has never seen, through a read-only query tool, and turning what
 * it finds into a set of dependent writes on the same endpoint.
 *
 * <p>Everything about the data is the model's: it learns the endpoint from its help, discovers the
 * schema, writes the queries, and makes every create and every append. The run keeps no schema, no
 * query and no rule for which record is the right one — nothing here would survive the model
 * finding out otherwise.
 *
 * <p>Code holds back only what would erase state. The reset is sent once at startup and refused to
 * the model; the orders that existed before the run are read right after it, and the model may not
 * delete them. A query reply that reached its row cap is marked as possibly cut off. See
 * {@link ApiTool} and {@link Seeded}.
 *
 * <p>Nothing here names the task: tool names, action names, the input file and the prompts are all
 * in the lesson bundle.
 */
public class S04E05 {

    private static final String LESSON = "s04e05";

    /**
     * The tier that earned s04e03 and s04e04. A preference, not the last word —
     * {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and {@code openrouter.model} each still
     * win over it.
     */
    private static final String MODEL = "google/gemini-3.8-flash";

    /**
     * Counts model round-trips. Help, a schema read, a handful of queries and a create, a signature
     * and an append per city fit well inside it, so this is a backstop rather than the expected exit.
     */
    private static final int MAX_ITERATIONS = 80;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    /** The one slot {@code user.md} fills: the needs file, as fetched. */
    private static final String NEEDS_SLOT = "{needs}";

    /** The envelope keys the run's own startup calls use; protocol vocabulary, as in {@link ApiTool}. */
    private static final String TOOL = "tool";
    private static final String ACTION = "action";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, LESSON);
        TaskParams task = lesson.task(TaskParams.class);
        Pattern flagPattern = Pattern.compile(task.flagPattern());
        Artifacts artifacts = Artifacts.of(labsConfig.dataDir(), LESSON);

        // Read and refused before the transcript opens: a blank prompt would buy a run of a model
        // that was told nothing, and a user.md without its slot would hand over no needs at all.
        String system = lesson.prompt("system.md");
        if (system.isBlank()) {
            throw new IllegalStateException("system.md in the s04e05 lesson bundle is blank. Write it before running.");
        }
        String userTemplate = lesson.prompt("user.md");
        int slots = userTemplate.split(Pattern.quote(NEEDS_SLOT), -1).length - 1;
        if (slots != 1) {
            throw new IllegalStateException(
                    "user.md has " + NEEDS_SLOT + " " + slots + " times; it needs it exactly once, for the"
                            + " needs file.");
        }

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("environment reset", task.resetTool() + ", sent by the run at startup");

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                LESSON,
                settings,
                List.of(labsConfig.hub().apiKey(), labsConfig.llm().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            // The reset, once, at startup and before the model sees anything. Sent here rather than
            // through the tool, which refuses it. Nothing resets in a finally — deliberately:
            // nothing else shares this state, the orders a failed run ends with are the evidence for
            // how it failed, and the next run's reset clears them anyway.
            //
            // Sent without retries, and its status checked: every model call after this assumes the
            // seeded state, so a refused reset ends the run here, before a token is spent.
            HubResponse reset = hub.send(
                    "reset · " + task.resetTool(), task.verifyTask(), Map.of(TOOL, task.resetTool()));
            if (reset.status() / 100 != 2) {
                throw new IllegalStateException(
                        "The startup reset was refused with status " + reset.status() + ": " + reset.body()
                                + ". The state is not the seeded one, so the run stops before the model"
                                + " is called. Check verifyTask and resetTool in the lesson's"
                                + " task.properties.");
            }

            // Read right after the reset, so the set is exactly the seeded orders and nothing a
            // previous run left behind.
            TaskParams.Orders orders = task.orders();
            Set<String> seeded = Seeded.ids(accepted(hub.send(
                    "seeded · " + orders.tool() + " · " + orders.get(),
                    task.verifyTask(),
                    Map.of(TOOL, orders.tool(), ACTION, orders.get()))), orders.list());
            transcript.note("seeded orders", String.join("\n", new TreeSet<>(seeded)));

            // fetched once; a re-run reads the cached copy
            String needs = read(hub.fetch(task.needs().url(), artifacts.file(task.needs().file())));

            ResilientHub resilient = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(List.of(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            Toolbox tools = new Toolbox(List.of(
                    new ApiTool(resilient, task.resetTool(), orders, seeded, task.database().tool())
                            .tool(task.api().name(), task.api().description())));

            List<Message> seed = List.of(
                    new Message(Role.system, system),
                    new Message(Role.user, userTemplate.replace(NEEDS_SLOT, needs)));

            System.out.println("Model: " + llm.model());

            // Asked after every reply, so the run ends on the reply itself rather than one model call
            // later. There is one tool, but the name is still checked: a second tool added later
            // should not be able to end the run by quoting a flag.
            String apiName = task.api().name();
            String flag = new Agent(recorded, tools, MAX_ITERATIONS).run(seed, (call, result) -> {
                if (!call.function().name().equals(apiName)) {
                    return Optional.empty();
                }
                Matcher found = flagPattern.matcher(result.text());
                return found.find() ? Optional.of(found.group()) : Optional.empty();
            });

            System.out.println(flag);
            transcript.outcome("Earned `" + flag + "`.");
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

    /** A reply the run can go on from: a 200 with a JSON body. Anything else ends the run here. */
    private static JsonNode accepted(HubResponse response) {
        if (response.status() != 200) {
            throw new IllegalStateException("Hub refused [" + response.status() + "]: " + response.body());
        }
        return MAPPER.readTree(response.body());
    }
}
