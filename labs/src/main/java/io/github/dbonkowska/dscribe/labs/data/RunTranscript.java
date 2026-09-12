package io.github.dbonkowska.dscribe.labs.data;

import io.github.dbonkowska.dscribe.llm.Transcript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * One run's record: what the model was sent, what it answered, what the hub was asked, and how
 * the run ended.
 *
 * <p>Written event by event rather than buffered — a run killed by an exception or a rate limit
 * still has a complete file up to the moment it died. Nothing here throws: a transcript that
 * took down the run it was documenting would be worse than no transcript at all.
 */
public final class RunTranscript implements Transcript, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunTranscript.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");
    private static final DateTimeFormatter READABLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;
    private final List<String> secrets;

    private int turn;
    private int rendered;
    private boolean ended;

    private RunTranscript(Path file, List<String> secrets) {
        this.file = file;
        this.secrets = secrets;
    }

    public static RunTranscript open(
            Path logsDir, String lesson, Map<String, String> settings, List<String> secrets) {

        LocalDateTime now = LocalDateTime.now();
        RunTranscript transcript =
                new RunTranscript(logsDir.resolve(lesson + "-" + STAMP.format(now) + ".md"), secrets);

        StringBuilder header = new StringBuilder()
                .append("# ").append(lesson).append(" · ").append(READABLE.format(now)).append("\n\n")
                .append("| setting | value |\n|---|---|\n");
        settings.forEach((key, value) ->
                header.append("| ").append(key).append(" | ").append(value).append(" |\n"));

        transcript.write(header.toString());
        return transcript;
    }

    public Path file() {
        return file;
    }

    private void write(String markdown) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    redact(markdown),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Cannot write transcript {}: {}", file, e.toString());
        }
    }

    /**
     * By value, not by field name: it does not matter which key a secret hides behind, whether a
     * provider echoes one back, or whether a later lesson passes one somewhere unexpected.
     */
    private String redact(String markdown) {
        String safe = markdown;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                safe = safe.replace(secret, "***");
            }
        }
        return safe;
    }

    /**
     * Payloads are written as they arrived apart from surrounding whitespace: OpenRouter holds a
     * non-streaming connection open with padding while a model thinks, and dozens of blank lines
     * ahead of the JSON tell a reader nothing. Nothing inside the payload is touched, so a
     * serialisation fault still shows up exactly as it went over the wire.
     */
    @Override
    public void append(String request, String response) {
        turn++;

        JsonNode sent = read(request);
        JsonNode back = read(response);
        JsonNode messages = sent.path("messages");

        StringBuilder block = new StringBuilder("\n## turn ").append(turn).append(" · model")
                .append(served(sent, back)).append("\n\n")
                .append(skim(back)).append("\n\n");

        // whatever the model wrote in its own voice this turn — the skim line above names only its
        // tool calls, and prose left unrendered survives nowhere but the raw block
        quote(block, back.at("/choices/0/message/content").asString(""));

        // some providers return the model's thinking separately from its answer, and this is where
        // a model that "said nothing" turns out to have done all of its work. Folded, because it
        // runs to thousands of characters and would otherwise bury the conversation around it.
        String reasoning = back.at("/choices/0/message/reasoning").asString("");
        if (!reasoning.isBlank()) {
            block.append("<details><summary>reasoning · ")
                    .append(reasoning.length())
                    .append(" chars</summary>\n\n");
            quote(block, reasoning);
            block.append("</details>\n\n");
        }

        for (int i = rendered; i < messages.size(); i++) {
            conversation(block, messages.get(i));
        }
        rendered = messages.size();

        block.append("<details><summary>raw request · ").append(messages.size())
                .append(" messages · ").append(sent.path("tools").size()).append(" tools</summary>\n\n")
                .append("```json\n").append(request.strip()).append("\n```\n</details>\n\n")
                .append("response · `finish_reason: ")
                .append(back.at("/choices/0/finish_reason").asString("")).append("`\n\n")
                .append("```json\n").append(response.strip()).append("\n```\n");

        write(block.toString());
    }

    /**
     * Which model answered, for the heading — the response's own word for it, falling back to
     * what the request asked for.
     *
     * <p>The response wins because a provider may route elsewhere than it was asked, and the
     * model that actually answered is the one a bad turn should be attributed to. It matters now
     * that a run can use more than one: a delegated read and the planning turn that asked for it
     * sit next to each other in this file, and without a name on each the reader cannot tell
     * which of the two reasoned badly.
     *
     * <p>Empty when neither half names one, so a payload that could not be parsed still gets a
     * heading rather than the word "null".
     */
    private static String served(JsonNode request, JsonNode response) {
        String model = response.path("model").asString("");
        if (model.isBlank()) {
            model = request.path("model").asString("");
        }
        return model.isBlank() ? "" : " · " + model;
    }

    /** What the model did this turn, in one line — the layer you read top to bottom. */
    private static String skim(JsonNode response) {
        JsonNode calls = response.at("/choices/0/message/tool_calls");
        if (calls.isEmpty()) {
            return "**assistant** → *(no tool calls)*";
        }

        StringBuilder line = new StringBuilder();
        calls.forEach(call -> line.append("**assistant** → `")
                .append(call.at("/function/name").asString(""))
                .append(" ")
                .append(call.at("/function/arguments").asString(""))
                .append("`\n"));
        return line.toString().stripTrailing();
    }

    /**
     * Assistant turns are skipped: each was already shown as the previous turn's skim line, and
     * repeating it here would double every tool call in the file.
     */
    private static void conversation(StringBuilder block, JsonNode message) {
        String role = message.path("role").asString("");
        if ("assistant".equals(role)) {
            return;
        }

        block.append("**").append(role).append("**");
        if (message.hasNonNull("tool_call_id")) {
            block.append(" ← `").append(message.path("tool_call_id").asString("")).append("`");
        }
        block.append("\n\n");

        quote(block, message.path("content").asString(""));
    }

    /** Text as a blockquote, line by line, so Markdown keeps the shape the model wrote. */
    private static void quote(StringBuilder block, String text) {
        if (text.isBlank()) {
            return;
        }
        text.lines().forEach(line -> block.append("> ").append(line).append("\n"));
        block.append("\n");
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (RuntimeException e) {
            // a payload we cannot parse is still worth keeping; the raw block below has it
            return MAPPER.createObjectNode();
        }
    }

    /**
     * The hop between the model asking and the model being answered: our request, the hub's reply.
     *
     * <p>Headers are kept because a rate limit announces itself in one and nowhere else. Which
     * name it uses is not known ahead of time, so they are written whole rather than filtered —
     * the file is where you find out. Redaction runs over them like everything else.
     */
    public void hubCall(
            String label, String path, int status, HttpHeaders headers, String request, String response) {

        write("\n## turn " + turn + " · hub · " + label + " → " + path + "\n\n"
                + "request · " + status + "\n\n"
                + "```json\n" + request.strip() + "\n```\n\n"
                + "response headers\n\n" + rendered(headers) + "\n"
                + "response\n\n"
                + "```json\n" + response.strip() + "\n```\n");
    }

    private static String rendered(HttpHeaders headers) {
        StringBuilder lines = new StringBuilder();
        headers.map().forEach((name, values) -> lines.append("- `")
                .append(name).append(": ").append(String.join(", ", values)).append("`\n"));
        return lines.isEmpty() ? "- *(none)*\n" : lines.toString();
    }

    /**
     * A pause the run took on purpose, and why.
     *
     * <p>Without this a rate-limited run reads as a gap between two hub calls, indistinguishable
     * from a slow server or a stall — and the waiting is the part of this lesson most worth being
     * able to see afterwards.
     */
    public void hubWait(String reason, Duration waited) {
        write("\n## turn " + turn + " · hub · waited " + waited.toSeconds() + "s — " + reason + "\n");
    }

    /** What the run made of itself: what was submitted, and what the hub said about it. */
    public void outcome(String summary) {
        ended = true;
        write("\n## outcome\n\n" + summary + "\n");
    }

    /**
     * The guarantee that replaces a {@code try}/{@code catch} in every runner. {@code close}
     * cannot see a propagating exception — Java does not hand it over — but it can record that the
     * run ended without saying anything for itself, and the last exchange above is the evidence.
     */
    @Override
    public void close() {
        if (!ended) {
            ended = true;
            write("\n## outcome\n\nRun ended without a recorded outcome — see the last exchange above.\n");
        }
    }
}
