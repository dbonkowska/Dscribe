package io.github.dbonkowska.dscribe.labs.s03e04;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import io.github.dbonkowska.dscribe.schema.SchemaUtils;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * A tool endpoint an external agent calls with a single free-text parameter, and a reply that has
 * to fit a fixed byte range.
 *
 * <p>No agent loop here: the caller is the one iterating, so a request is answered with one
 * structured-output call to resolve the query, then a plain code join and a bounded reply. See
 * {@link ItemLookup} for that whole per-request decision, and {@link ToolReply}/
 * {@link ReplyBounds} for what keeps a reply inside the range.
 *
 * <p>The transcript lives as long as the server does. The caller can go on calling after the
 * result has been read, and a record that closed at the result would stop while the run went on
 * being spent.
 */
public class S03E04 {

    private static final Logger log = LoggerFactory.getLogger(S03E04.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * The model this lesson's flag was earned on, pinned so version control records it —
     * {@code labs/data} is gitignored, so the transcript header alone would not.
     *
     * <p>Only one model has been tried. The resolver is one structured call per request over a
     * catalog carried in the system prompt, so it asks little of a model beyond matching a
     * paraphrase to a name, and a cheaper tier is untested rather than ruled out.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL}
     * and {@code openrouter.model} each still win over it, which is what makes trying another one
     * a flag rather than an edit.
     */
    private static final String MODEL = "anthropic/claude-haiku-4.5";

    private static final int PORT = 8080;

    /** How many times to check before giving up and pointing at the hub's own debug panel. */
    private static final int CHECK_ATTEMPTS = 12;

    /** The lesson says results take 30-60 seconds; twelve checks ten seconds apart covers that
     *  with room to spare, without checking so often the hub sees it as abuse. */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(10);

    /**
     * The resolver's structured answer: zero or more item codes the query plausibly describes.
     *
     * <p>{@code reasoning} is here to be read in the transcript, not by the code. The schema
     * generator orders properties by name, so it is written after the codes rather than before,
     * which makes it an account of the answer and not the working that produced it.
     */
    record ItemCodes(String reasoning, List<String> codes) {}

    /** One response, as it will be sent and as it will be recorded. */
    private record Reply(int status, String contentType, String body) {}

    public static void main(String[] args) throws IOException, InterruptedException {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s03e04");
        TaskParams task = lesson.task(TaskParams.class);
        Pattern resultPattern = Pattern.compile(task.flagPattern());

        // changes on every tunnel reconnect, so there is no default to fall back to — unlike the
        // model, this is not a preference to override, it is a fact about this run that has to be
        // supplied fresh every time
        String tunnelUrl = tunnelUrl();

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("port", String.valueOf(PORT));
        settings.put("tool path", task.toolPath());
        settings.put("public tool url", tunnelUrl + task.toolPath());

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s03e04",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            String citiesCsv = lesson.prompt(task.citiesFile());
            String itemsCsv = lesson.prompt(task.itemsFile());
            String connectionsCsv = lesson.prompt(task.connectionsFile());
            Catalog catalog = Catalog.of(citiesCsv, itemsCsv, connectionsCsv);

            // rendered here, before the server exists: a prompt with no slot for the catalog is
            // refused now rather than resolving every query against nothing
            String system = CatalogPrompt.render(lesson.prompt("system.md"), itemsCsv);
            ItemLookup.Resolver resolver = resolver(recorded, system);

            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext(task.toolPath(),
                    exchange -> handle(exchange, catalog, resolver, task, transcript));

            // no executor, as in S01E03: every handler runs on the dispatcher thread, one at a
            // time, which is what makes a single shared RunTranscript safe to write to here
            server.start();

            AtomicReference<String> result = new AtomicReference<>("the check had not finished");

            // Ctrl-C is how this run ends, and it does not unwind main — so the outcome and the
            // usage table are written here, once the server has stopped taking calls. close() is
            // idempotent, so the try-with-resources reaching it on a startup failure is harmless.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop(0);
                transcript.outcome("Server stopped. The check said: " + result.get());
                transcript.close();
            }));

            System.out.println("Model: " + llm.model());
            System.out.println("Listening on http://localhost:" + PORT + task.toolPath());
            System.out.println("Public tool URL: " + tunnelUrl + task.toolPath());
            System.out.println("Transcript: " + transcript.file());

            HubResponse registered = hub.send("register", task.verifyTask(),
                    Map.of("tools", List.of(
                            toolRegistration(tunnelUrl + task.toolPath(), task.tool().description()))));
            System.out.println("Registration: " + registered.status() + " " + registered.body());

            Optional<String> flag = new ResultPoll(
                    () -> check(hub, task.verifyTask()),
                    resultPattern, Sleeper.real(), CHECK_ATTEMPTS, CHECK_INTERVAL).run();

            String found = flag
                    .map(text -> "Earned `" + text + "`.")
                    .orElse("No result after " + CHECK_ATTEMPTS + " checks, "
                            + CHECK_INTERVAL.toSeconds() + "s apart. Check "
                            + labsConfig.hub().baseUrl() + "/debug by hand.");
            System.out.println(found);
            transcript.note("result", found);
            result.set(found);

            // the caller may still be calling, and the transcript's scope is the server's life,
            // so main has to outlive the poll
            System.out.println("Still serving. Ctrl-C to stop.");
            Thread.currentThread().join();
        }
    }

    /**
     * One check. A check that fails is a check that found nothing: an exception here would end
     * {@code main} while the server's dispatcher thread kept the process alive with no one
     * watching it.
     */
    private static String check(HubClient hub, String verifyTask) {
        try {
            HubResponse response = hub.send("check", verifyTask, Map.of("action", "check"));
            System.out.println("Check: " + response.status() + " " + response.body());
            return response.body();
        } catch (RuntimeException e) {
            log.warn("Check failed: {}", e.toString());
            return e.toString();
        }
    }

    private static String tunnelUrl() {
        String property = System.getProperty("tunnel.url");
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv("TUNNEL_URL");
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException(
                "No tunnel URL configured. Set -Dtunnel.url=... or the TUNNEL_URL environment"
                        + " variable to this process's current public address. It changes on"
                        + " every tunnel reconnect, so task.properties cannot hold a default.");
    }

    /**
     * One turn: whatever the caller sent, answered without any memory of an earlier request.
     *
     * <p>Every branch is recorded, and recorded before the reply is sent — a malformed call is the
     * exchange most worth reading, and a client that hangs up mid-response must not take the
     * record with it.
     *
     * <p>A blank {@code params} gets the no-match reply rather than a 400: the caller stops if it
     * gets no reply at all, and a blank argument is not the same mistake as a body that is not
     * JSON. Nothing here may end the process; a malformed request comes back as 400 or 405 and the
     * server keeps listening.
     */
    private static void handle(
            HttpExchange exchange, Catalog catalog, ItemLookup.Resolver resolver,
            TaskParams task, RunTranscript transcript) throws IOException {

        String label = "tool · " + task.tool().name();

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            Reply reply = plain(405, "POST a JSON object with a \"params\" field to this endpoint.");
            record(transcript, label, "(" + exchange.getRequestMethod() + ", body not read)", reply, "");
            send(exchange, reply);
            return;
        }

        String rawRequest = "";
        JsonNode request;
        try (InputStream body = exchange.getRequestBody()) {
            rawRequest = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            request = MAPPER.readTree(rawRequest);
        } catch (RuntimeException e) {
            log.warn("Malformed request body: {}", e.toString());
            Reply reply = plain(400, "Malformed request body.");
            record(transcript, label, rawRequest, reply, "not JSON: " + e);
            send(exchange, reply);
            return;
        }

        if (!request.isObject()) {
            Reply reply = plain(400, "Expected a JSON object with a \"params\" field.");
            record(transcript, label, rawRequest, reply, "not a JSON object");
            send(exchange, reply);
            return;
        }

        String query = request.path("params").asString("");

        String output;
        String extra = "";
        try {
            if (query.isBlank()) {
                output = ToolReply.NO_MATCH;
            } else {
                ItemLookup.Answer answer = ItemLookup.answer(
                        query, catalog, resolver, task.replyMinBytes(), task.replyMaxBytes());
                output = answer.reply();
                if (!answer.unknownCodes().isEmpty()) {
                    extra = "dropped codes the catalog does not know: " + answer.unknownCodes();
                }
            }
        } catch (RuntimeException e) {
            // a failed lookup is not a wording problem, so it does not get the no-match reply:
            // told to rephrase, the caller would rewrite a query that only needed sending again
            log.warn("Resolution failed for query '{}'", query, e);
            output = ToolReply.LOOKUP_FAILED;
            extra = "lookup failed: " + e;
        }

        Reply reply = new Reply(200, "application/json; charset=utf-8",
                MAPPER.writeValueAsString(Map.of("output", output)));
        record(transcript, label, rawRequest, reply, extra);
        send(exchange, reply);
    }

    private static Reply plain(int status, String message) {
        return new Reply(status, "text/plain; charset=utf-8", message);
    }

    private static void record(
            RunTranscript transcript, String label, String request, Reply reply, String extra) {

        transcript.note(label,
                "request:\n```\n" + request.strip() + "\n```\n\n"
                        + "response · " + reply.status() + ":\n```\n" + reply.body().strip() + "\n```"
                        + (extra.isEmpty() ? "" : "\n\n" + extra));
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * One structured-output call resolves the query against the catalog. The catalog is already in
     * the system prompt, rendered once at startup, so a call sends it and the query and nothing
     * else.
     */
    private static ItemLookup.Resolver resolver(LlmClient llm, String system) {
        ObjectNode schema = SchemaUtils.from(ItemCodes.class);

        return query -> {
            List<Message> messages = List.of(
                    new Message(Role.system, system),
                    new Message(Role.user, query));

            ItemCodes result = llm.sendStructured(
                    messages, ResponseFormat.jsonSchema("item_codes", schema), ItemCodes.class);

            return result == null || result.codes() == null ? List.of() : result.codes();
        };
    }

    private static ObjectNode toolRegistration(String url, String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("URL", url);
        node.put("description", description);
        return node;
    }
}
