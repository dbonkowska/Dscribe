package io.github.dbonkowska.dscribe.llm;

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
import java.util.List;

public class LlmClient implements ChatTransport {

    private static final String CHAT_COMPLETIONS = "/chat/completions";

    // both are thread-safe and stateless once built, so every client instance can share them
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final LlmConfig config;
    private final String model;
    private final Transcript transcript;

    public LlmClient(LlmConfig config){
        this(config, config.model(), Transcript.NONE);
    }

    private LlmClient(LlmConfig config, String model, Transcript transcript){
        this.config = config;
        this.model = model;
        this.transcript = transcript;
    }

    /** A copy of this client talking to a different model. Handy for comparing models in one run. */
    public LlmClient withModel(String model){
        return new LlmClient(config, model, transcript);
    }

    /** A copy of this client recording every exchange it makes. */
    public LlmClient withTranscript(Transcript transcript){
        return new LlmClient(config, model, transcript);
    }

    public String model(){
        return model;
    }

    public <T> T sendStructured(List<Message> messages, ResponseFormat responseFormat, Class<T> type) {
        String content = firstChoice(
                exchange(new ChatRequest(model, messages, responseFormat)))
                .message()
                .content();

        return MAPPER.readValue(content, type);
    }

    @Override
    public ChatResponse.Choice send(List<Message> messages, List<ToolSpec> tools, String toolChoice) {
        return firstChoice(exchange(new ChatRequest(model, messages, null, tools, toolChoice)));
    }

    private ChatResponse exchange(ChatRequest requestBody) {
        try {
            String json = MAPPER.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + CHAT_COMPLETIONS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            // recorded before the status check: a 429 is exactly the exchange worth keeping
            transcript.append(json, response.body());

            if (response.statusCode() != 200) {
                throw new RuntimeException("API error [" + response.statusCode() + "]: " + response.body());
            }

            return MAPPER.readValue(response.body(), ChatResponse.class);

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