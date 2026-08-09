package io.github.dbonkowska.dscribe.config;

public interface LlmConfig {

    String apiKey();

    String model();

    /** Override to talk to a different OpenAI-compatible endpoint, e.g. a local Ollama server. */
    default String baseUrl() {
        return "https://openrouter.ai/api/v1";
    }
}