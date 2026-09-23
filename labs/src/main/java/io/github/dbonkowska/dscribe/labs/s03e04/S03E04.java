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

/**
 * A tool endpoint the hub's own agent calls, not the project's {@link io.github.dbonkowska.dscribe.agent.Agent}.
 * One tool, resolving a free-text description of an item to the cities that sell it — the hub's
 * agent calls it once per item and works out for itself which city carries all of them.
 *
 * <p>No agent loop here: the caller is the one iterating, so a request is answered with a single
 * structured-output call to resolve the query, then a plain code join and a bounded reply. See
 * {@link ItemLookup} for that whole per-request decision, and {@link ToolReply}/
 * {@link ReplyBounds} for what keeps a reply inside the exercise's byte range.
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

    /** How many times to poll before giving up and pointing at the hub's own debug panel. */
    private static final int CHECK_ATTEMPTS = 12;

    /** The lesson says results take 30-60 seconds; twelve checks ten seconds apart covers that
     *  with room to spare, without polling so often the hub sees it as abuse. */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(10);

    /**
     * The resolver's structured answer: zero or more item codes the query plausibly describes.
     *
     * <p>Component order is schema order, and schema order is the order a strict structured
     * answer is generated in — {@code reasoning} comes first so it is worked out before the
     * codes it justifies, not written after them as a rationalisation. Nothing reads it back; it
     * exists to be produced, and to explain a broad or surprising match in the transcript.
     */
    record ItemCodes(String reasoning, List<String> codes) {}

    public static void main(String[] args) throws IOException {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s03e04");
        TaskParams task = lesson.task(TaskParams.class);

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

            ItemLookup.Resolver resolver = resolver(recorded, itemsCsv, lesson.prompt("system.md"));

            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext(task.toolPath(),
                    exchange -> handle(exchange, catalog, resolver, task, transcript));

            // no executor, as in S01E03: every handler runs on the dispatcher thread, one at a
            // time, which is what makes a single shared RunTranscript safe to write to here
            server.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop(0);
                transcript.outcome("Server stopped.");
            }));

            System.out.println("Model: " + llm.model());
            System.out.println("Listening on http://localhost:" + PORT + task.toolPath());
            System.out.println("Public tool URL: " + tunnelUrl + task.toolPath());
            System.out.println("Transcript: " + transcript.file());

            HubResponse registered = hub.send("register", task.verifyTask(),
                    Map.of("tools", List.of(
                            toolRegistration(tunnelUrl + task.toolPath(), task.tool().description()))));
            System.out.println("Registration: " + registered.status() + " " + registered.body());

            String outcome = pollForResult(hub, task.verifyTask());
            System.out.println(outcome);
            transcript.outcome(outcome);
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
     * One turn: whatever the hub's agent sent, answered without any memory of an earlier request.
     *
     * <p>A blank {@code params} gets the no-match reply rather than a 400 — the exercise says the
     * calling agent stops if it gets no reply at all, and a blank argument is not the same mistake
     * as a body that is not JSON. Nothing here may end the process; a malformed request comes back
     * as 400 or 405 and the server keeps listening.
     */
    private static void handle(
            HttpExchange exchange, Catalog catalog, ItemLookup.Resolver resolver,
            TaskParams task, RunTranscript transcript) throws IOException {

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            respondPlain(exchange, 405, "POST a JSON object with a \"params\" field to this endpoint.");
            return;
        }

        String rawRequest;
        JsonNode request;
        try (InputStream body = exchange.getRequestBody()) {
            rawRequest = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            request = MAPPER.readTree(rawRequest);
        } catch (RuntimeException e) {
            log.warn("Malformed request body: {}", e.toString());
            respondPlain(exchange, 400, "Malformed request body.");
            return;
        }

        if (!request.isObject()) {
            respondPlain(exchange, 400, "Expected a JSON object with a \"params\" field.");
            return;
        }

        String query = request.path("params").asString("");

        String output;
        try {
            output = query.isBlank()
                    ? ToolReply.NO_MATCH
                    : ItemLookup.answer(
                            query, catalog, resolver, task.replyMinBytes(), task.replyMaxBytes());
        } catch (RuntimeException e) {
            // a resolution failure still gets a reply the agent can read and act on, rather than
            // ending its run on nothing at all
            log.warn("Resolution failed for query '{}'", query, e);
            output = ToolReply.NO_MATCH;
        }

        String responseJson = respondJson(exchange, 200, output);
        transcript.note("tool · " + task.tool().name(),
                "request:\n```json\n" + rawRequest.strip() + "\n```\n\n"
                        + "response:\n```json\n" + responseJson.strip() + "\n```");
    }

    private static void respondPlain(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String respondJson(HttpExchange exchange, int status, String output) throws IOException {
        String json = MAPPER.writeValueAsString(Map.of("output", output));
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        return json;
    }

    /**
     * One structured-output call resolves the query against the item catalog. The catalog text is
     * folded into the system prompt once, at construction — not re-sent as a separate message per
     * call, which would cost the same tokens for no benefit.
     */
    private static ItemLookup.Resolver resolver(LlmClient llm, String itemsCatalogText, String systemTemplate) {
        ObjectNode schema = SchemaUtils.from(ItemCodes.class);
        String system = systemTemplate.formatted(itemsCatalogText);

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

    /**
     * Waits {@link #CHECK_INTERVAL} between attempts via {@link Sleeper#real()} — the same
     * interruptible wait {@code ResilientHub} uses, so a Ctrl-C during a long poll behaves the
     * same way here as it does everywhere else in this module.
     */
    private static String pollForResult(HubClient hub, String verifyTask) {
        Sleeper sleeper = Sleeper.real();
        Map<String, String> check = Map.of("action", "check");

        for (int attempt = 1; attempt <= CHECK_ATTEMPTS; attempt++) {
            sleeper.await(CHECK_INTERVAL);

            HubResponse response = hub.send("check", verifyTask, check);
            if (response.status() == 200) {
                return response.body();
            }
            System.out.println("Check " + attempt + "/" + CHECK_ATTEMPTS + ": "
                    + response.status() + " " + response.body());
        }

        return "No result after " + CHECK_ATTEMPTS + " checks, " + CHECK_INTERVAL.toSeconds()
                + "s apart. Check https://hub.ag3nts.org/debug by hand.";
    }
}
