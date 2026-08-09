package io.github.dbonkowska.dscribe.labs.config;

import io.github.dbonkowska.dscribe.config.LlmConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

/**
 * @param lessonsDir where per-lesson prompts and task parameters live. Kept outside the
 *                   repository — those describe the course exercise, not the framework.
 */
public record LabsConfig(Llm llm, Hub hub, Path lessonsDir) {

    private static final String DEFAULT_MODEL = "qwen/qwen3.5-9b";
    private static final String DEFAULT_LLM_BASE_URL = "https://openrouter.ai/api/v1";
    private static final String DEFAULT_HUB_BASE_URL = "https://hub.ag3nts.org";

    public record Llm(String apiKey, String model, String baseUrl) implements LlmConfig {}

    public record Hub(String apiKey, String baseUrl, String verifyUrl) {}

    public static LabsConfig load() {
        Properties props = new Properties();
        try (InputStream in = LabsConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in == null) throw new RuntimeException("application.properties not found on classpath");
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Llm llm = new Llm(
                require(props, "openrouter.api.key"),
                resolveModel(props),
                orDefault(props, "openrouter.base.url", DEFAULT_LLM_BASE_URL)
        );

        String hubBaseUrl = orDefault(props, "hub.base.url", DEFAULT_HUB_BASE_URL);

        Hub hub = new Hub(
                require(props, "hub.api.key"),
                hubBaseUrl,
                orDefault(props, "hub.verify", hubBaseUrl + "/verify")
        );

        return new LabsConfig(llm, hub, Path.of(require(props, "labs.lessons.dir")));
    }

    /** -Dopenrouter.model wins, then OPENROUTER_MODEL, then application.properties, then the default. */
    private static String resolveModel(Properties props) {
        String override = System.getProperty("openrouter.model");
        if (override == null || override.isBlank()) {
            override = System.getenv("OPENROUTER_MODEL");
        }
        if (override != null && !override.isBlank()) {
            return override;
        }
        return orDefault(props, "openrouter.model", DEFAULT_MODEL);
    }

    /** Unlike Properties#getProperty, treats a present-but-blank value as absent. */
    static String orDefault(Properties props, String key, String fallback) {
        String value = props.getProperty(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String require(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) throw new RuntimeException("Missing required property: " + key);
        return value;
    }
}
