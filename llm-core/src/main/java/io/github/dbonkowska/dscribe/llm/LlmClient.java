package io.github.dbonkowska.dscribe.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.github.dbonkowska.dscribe.config.LlmConfig;
import io.github.dbonkowska.dscribe.conversation.Message;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LlmClient implements ChatTransport {

    private static final String CHAT_COMPLETIONS = "/chat/completions";
    private static final String AUDIO_TRANSCRIPTIONS = "/audio/transcriptions";
    private static final String AUDIO_SPEECH = "/audio/speech";
    private static final String AUDIO_MPEG = "audio/mpeg";

    // both are thread-safe and stateless once built, so every client instance can share them
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final LlmConfig config;
    private final String model;
    private final Transcript transcript;
    private final Reasoning reasoning;

    public LlmClient(LlmConfig config){
        this(config, config.model(), Transcript.NONE, null);
    }

    private LlmClient(LlmConfig config, String model, Transcript transcript, Reasoning reasoning){
        this.config = config;
        this.model = model;
        this.transcript = transcript;
        this.reasoning = reasoning;
    }

    /**
     * A copy of this client talking to {@code model} — unless the configuration named one, which
     * wins. The caller states a preference; whether it has the final say is decided here and
     * nowhere else, so a caller cannot drop the override by forgetting to consult it.
     *
     * <p>Whatever {@link LlmConfig#model()} resolves from — a command-line flag, an environment
     * variable, a properties file — has already been decided by the time it arrives, and a blank
     * one counts as absent.
     */
    public LlmClient defaultModel(String model){
        return new LlmClient(config, named(config.model()) ? config.model() : model, transcript, reasoning);
    }

    /** A copy of this client recording every exchange it makes. */
    public LlmClient withTranscript(Transcript transcript){
        return new LlmClient(config, model, transcript, reasoning);
    }

    /**
     * A copy of this client asking every call to reason at {@code effort} — for a caller with a
     * deadline and a model whose reasoning cannot be switched off. A blank effort counts as none,
     * leaving it to the provider's default.
     */
    public LlmClient reasoningEffort(String effort){
        return new LlmClient(config, model, transcript, named(effort) ? Reasoning.effort(effort) : null);
    }

    /** What {@link #sendStructured} sends, built apart from the sending so it can be asserted. */
    ChatRequest structuredRequest(List<Message> messages, ResponseFormat responseFormat) {
        return new ChatRequest(model, messages, responseFormat, null, null, reasoning);
    }

    /** What {@link #send} sends, built apart from the sending so it can be asserted. */
    ChatRequest toolRequest(List<Message> messages, List<ToolSpec> tools, String toolChoice) {
        return new ChatRequest(model, messages, null, tools, toolChoice, reasoning);
    }

    /** What {@link #transcribe} sends, built apart from the sending so it can be asserted. */
    TranscriptionRequest transcriptionRequest(byte[] audio, String format) {
        return new TranscriptionRequest(
                model, new TranscriptionRequest.InputAudio(Base64.getEncoder().encodeToString(audio), format));
    }

    /**
     * What {@link #speak} sends, built apart from the sending so it can be asserted. Blank text or a
     * blank voice is refused here, before a request the provider would refuse with a message a long
     * way from this call.
     */
    SpeechRequest speechRequest(String text, String voice, String instructions) {
        if (!named(text)) {
            throw new IllegalArgumentException("Nothing to say: input is blank.");
        }
        if (!named(voice)) {
            throw new IllegalArgumentException("No voice to say it in: voice is blank. Name one the model offers.");
        }
        return new SpeechRequest(model, text, voice, named(instructions) ? instructions : null, SpeechRequest.MP3);
    }

    /**
     * The speech endpoint names its usage differently from chat — {@code input_tokens} where chat
     * says {@code prompt_tokens} — so it is read through its own shape and then carried as the one
     * {@link Usage} every report sums. Read with chat's names it would parse as zero tokens and
     * still look like a figure.
     */
    static Transcription parseTranscription(String body) {
        TranscriptionReply reply = MAPPER.readValue(body, TranscriptionReply.class);
        SpeechUsage usage = reply.usage();
        return new Transcription(
                reply.text(),
                usage == null ? null : new Usage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), usage.cost()));
    }

    /**
     * What the audio says, from the speech-to-text endpoint — a different path from chat, with the
     * same rules: refused without a model, recorded before the status check, spend reported once the
     * reply has parsed.
     *
     * <p>The audio travels in the request whole. Anything recording it should elide the payload
     * rather than write it out.
     *
     * @param format the container, e.g. {@code mp3}
     */
    public Transcription transcribe(byte[] audio, String format) {
        requireModel();
        Transcription heard = parseTranscription(post(AUDIO_TRANSCRIPTIONS, transcriptionRequest(audio, format)));

        // the reply names no model, so the spend goes to the one that was asked
        if (heard.usage() != null) {
            transcript.usage(attributed(null, model), heard.usage());
        }
        return heard;
    }

    /**
     * {@code text} read aloud, as MP3, from the text-to-speech endpoint — the one call whose reply is
     * not JSON. Refused without a model, recorded before the status check like every other call.
     *
     * <p>A recording is written to the transcript by its size, never as bytes. Anything that is not
     * one — an error, or a 200 that turns out not to be audio — is written as the text it is,
     * because that is the reply worth reading.
     *
     * <p>A 200 is still refused unless it is non-empty {@code audio/mpeg}. Otherwise the bytes would
     * travel on as a recording, and whoever received them would reject them for a reason that
     * points nowhere near here.
     *
     * <p>The reply prices nothing, so the spend is reported as null: unpriced, not free.
     *
     * @param instructions delivery hints such as tone; blank for none
     */
    public byte[] speak(String text, String voice, String instructions) {
        requireModel();
        String json = MAPPER.writeValueAsString(speechRequest(text, voice, instructions));
        HttpResponse<byte[]> response = exchange(AUDIO_SPEECH, json, HttpResponse.BodyHandlers.ofByteArray());

        byte[] body = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        boolean audio = response.statusCode() == 200 && contentType.startsWith(AUDIO_MPEG);

        Map<String, Object> recorded = new LinkedHashMap<>();
        recorded.put("audio", "<" + body.length + " bytes " + contentType + ">");
        recorded.put("generation", response.headers().firstValue("X-Generation-Id").orElse(null));
        transcript.append(json, audio ? MAPPER.writeValueAsString(recorded) : new String(body, StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "API error [" + response.statusCode() + "]: " + new String(body, StandardCharsets.UTF_8));
        }
        if (!audio) {
            String start = new String(body, StandardCharsets.UTF_8);
            throw new RuntimeException("Speech came back as " + (contentType.isEmpty() ? "no content type" : contentType)
                    + ", not " + AUDIO_MPEG + ": " + start.substring(0, Math.min(200, start.length())));
        }
        if (body.length == 0) {
            throw new RuntimeException("Speech came back as 0 bytes of " + AUDIO_MPEG + ": nothing to play.");
        }

        transcript.usage(attributed(null, model), null);
        return body;
    }

    private record TranscriptionReply(String text, SpeechUsage usage) {}

    private record SpeechUsage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens,
            @JsonProperty("total_tokens") int totalTokens,
            double cost) {}

    public String model(){
        return model;
    }

    public <T> T sendStructured(List<Message> messages, ResponseFormat responseFormat, Class<T> type) {
        String content = firstChoice(exchange(structuredRequest(messages, responseFormat)))
                .message()
                .text();

        return MAPPER.readValue(content, type);
    }

    @Override
    public ChatResponse.Choice send(List<Message> messages, List<ToolSpec> tools, String toolChoice) {
        return firstChoice(exchange(toolRequest(messages, tools, toolChoice)));
    }

    private static boolean named(String model) {
        return model != null && !model.isBlank();
    }

    /**
     * Which model a call's spend is recorded against: the one that answered, falling back to the
     * one that was asked.
     *
     * <p>The response wins because a provider may route elsewhere than it was asked, and a run
     * that used more than one model cannot be read afterwards unless each exchange is attributed
     * to whatever actually produced it. {@code RunTranscript.served} already applies this rule to
     * its headings; this applies it to the bill.
     *
     * <p>The fallback exists because a response that names nothing would otherwise accumulate a
     * whole run's spend under a blank key, which identifies nothing in a report. Absent and empty
     * both arrive here, and {@link #named} treats them alike.
     */
    static String attributed(String served, String requested) {
        return named(served) ? served : requested;
    }

    private ChatResponse exchange(ChatRequest requestBody) {
        requireModel();
        ChatResponse parsed = MAPPER.readValue(post(CHAT_COMPLETIONS, requestBody), ChatResponse.class);

        // after the status check and the parse, unlike the raw halves: a call the provider
        // rejected spent nothing, and one that did not parse has no figure to report
        if (parsed.usage() != null) {
            transcript.usage(attributed(parsed.model(), model), parsed.usage());
        }

        return parsed;
    }

    private void requireModel() {
        if (!named(model)) {
            // nothing here invents a model: a request that names none is answered by the provider
            // with an error a long way from the config key that caused it
            throw new IllegalStateException(
                    "No model to call: the configuration names none and no default was given. "
                            + "Set one in LlmConfig, or call defaultModel(...).");
        }
    }

    /** One POST to the provider, recorded before its status is looked at; the body of a 200. */
    private String post(String path, Object requestBody) {
        String json = MAPPER.writeValueAsString(requestBody);
        HttpResponse<String> response = exchange(path, json, HttpResponse.BodyHandlers.ofString());

        // recorded before the status check: a 429 is exactly the exchange worth keeping
        transcript.append(json, response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API error [" + response.statusCode() + "]: " + response.body());
        }

        return response.body();
    }

    /**
     * The request every call makes, whatever its reply is read as — text for JSON, bytes for audio.
     * Recording is the caller's, because only the caller knows how its reply can be written down.
     */
    private <T> HttpResponse<T> exchange(String path, String json, HttpResponse.BodyHandler<T> reply) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return HTTP.send(request, reply);

        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static ChatResponse.Choice firstChoice(ChatResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new RuntimeException("API returned no choices");
        }
        return response.choices().getFirst();
    }
}