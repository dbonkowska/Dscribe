package io.github.dbonkowska.dscribe.labs.s01e03;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dbonkowska.dscribe.agent.Agent;
import io.github.dbonkowska.dscribe.agent.StopCondition;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.labs.session.SessionStore;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.Toolbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first lesson that does not run once and exit. An operator talks to the persona over HTTP,
 * one request per turn, and the conversation has to still be there on the next request — so the
 * process stays up, and {@link SessionStore} holds what was said under each session id.
 *
 * <p>There is no answer tool and nothing to submit: this lesson's flag arrives as an ordinary
 * operator message inside the conversation, which is why the transcript is the artefact the run
 * produces.
 */
public class S01E03 {

    private static final Logger log = LoggerFactory.getLogger(S01E03.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * The model this lesson's flag was earned on, pinned so version control records it —
     * {@code labs/data} is gitignored, so the transcript header alone would not.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it, which is what makes trying another one
     * a flag rather than an edit.
     */
    private static final String MODEL = "anthropic/claude-haiku-4.5";

    /**
     * Counts model round-trips, not tool calls: one turn may dispatch several. Deliberately small.
     * This bounds one operator turn rather than a whole run, and the operator is holding an open
     * request while it spends.
     */
    private static final int MAX_ITERATIONS = 5;

    private static final int PORT = 8080;

    /** Local, and only local: what the exercise reaches is whatever a tunnel maps onto this. */
    private static final String PATH = "/";

    /** The API's two verbs. Structure rather than content, like the argument field names below. */
    private static final String CHECK = "check";

    private static final String REDIRECT = "redirect";

    /** Argument shapes the model fills in. Field names are what the exercise's API asks for. */
    record CheckQuery(String packageid) {}

    record RedirectQuery(String packageid, String destination, String code) {}

    public static void main(String[] args) throws IOException, InterruptedException {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("port", String.valueOf(PORT));

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s01e03",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            Lesson lesson = Lesson.of(labsConfig, "s01e03");
            TaskParams task = lesson.task(TaskParams.class);

            Toolbox tools = new Toolbox(List.of(
                    packagesTool(hub, task.packagesPath(), CHECK, task.check(), CheckQuery.class),
                    packagesTool(hub, task.packagesPath(), REDIRECT, task.redirect(), RedirectQuery.class)));

            Agent agent = new Agent(recorded, tools, MAX_ITERATIONS);
            SessionStore sessions = new SessionStore();

            // read once: the persona is the same for every session, and re-reading it per request
            // would let the file change underneath a conversation already half-held
            Message persona = new Message(Role.system, lesson.prompt("system.md"));

            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext(PATH, exchange -> handle(exchange, agent, sessions, persona));

            // no executor on purpose. The default runs every handler on the dispatcher thread, one
            // at a time, and that is what makes a single shared RunTranscript safe — thread-safe
            // transcript writes are out of scope, and this is the decision that earns it.
            server.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop(0);
                transcript.outcome("Server stopped.");
            }));

            System.out.println("Model: " + llm.model());
            System.out.println("Listening on http://localhost:" + PORT + PATH);
            System.out.println("Transcript: " + transcript.file());
            System.out.println("Ctrl-C to stop.");

            // the transcript's scope is the server's life, so main has to outlive the handlers
            Thread.currentThread().join();
        }
    }

    /**
     * One operator turn: whatever was said under this session id, plus what they just said, run
     * to the model's own words.
     *
     * <p>Nothing in here may end the process. A malformed body is the caller's problem and comes
     * back as 400; anything else is ours and comes back as 500 with its stack trace logged. Either
     * way the server is still listening for the next turn.
     */
    private static void handle(HttpExchange exchange, Agent agent, SessionStore sessions, Message persona)
            throws IOException {

        String sessionId;
        String said;
        try (InputStream body = exchange.getRequestBody()) {
            // UTF-8 named explicitly, here and on the way out: the operator writes Polish, and a
            // platform default would mangle it into something the model then answers earnestly
            JsonNode request = MAPPER.readTree(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            sessionId = request.path("sessionID").asString("");
            said = request.path("msg").asString("");
        } catch (RuntimeException e) {
            log.warn("Malformed request body: {}", e.toString());
            respond(exchange, 400, "Malformed request body.");
            return;
        }

        try {
            List<Message> history = new ArrayList<>(sessions.load(sessionId));
            if (history.isEmpty()) {
                history.add(persona);
            }
            history.add(new Message(Role.user, said));

            List<Message> answered = agent.run(history, StopCondition.untilNoToolCalls());
            sessions.save(sessionId, answered);

            // untilNoToolCalls guarantees the last turn is the model talking rather than calling,
            // but a provider may still hand that turn back with no content at all
            String reply = answered.getLast().content();
            respond(exchange, 200, reply == null ? "" : reply);
        } catch (RuntimeException e) {
            log.warn("Turn failed for session {}", sessionId, e);
            respond(exchange, 500, "The agent could not answer that turn.");
        }
    }

    private static void respond(HttpExchange exchange, int status, String msg) throws IOException {
        byte[] body = MAPPER.writeValueAsString(Map.of("msg", msg)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Both tools are the same POST to the same endpoint, differing in the action they carry and
     * the arguments the model is asked for. The action is fixed here rather than declared in the
     * schema: a model free to choose it could redirect a package while claiming to check one.
     */
    private static <A> Tool<A> packagesTool(
            HubClient hub, String path, String action, TaskParams.PackageTool spec, Class<A> argumentType) {

        return new Tool<>(
                spec.name(),
                spec.description(),
                argumentType,
                query -> hub.post(spec.name(), path, withAction(action, query)));
    }

    private static ObjectNode withAction(String action, Object query) {
        ObjectNode json = MAPPER.valueToTree(query);
        json.put("action", action);
        return json;
    }
}
