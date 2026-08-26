package io.github.dbonkowska.dscribe.labs.config;

import io.github.dbonkowska.dscribe.config.LlmConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;

/**
 * @param lessonsDir where per-lesson prompts and task parameters live. Kept outside the
 *                   repository — those describe the course exercise, not the framework.
 * @param dataDir    where a lesson's runtime files live: what it downloaded, what it produced.
 *                   Inside the repository but ignored by git.
 */
public record LabsConfig(Llm llm, Hub hub, Path lessonsDir, Path dataDir) {

    private static final String DEFAULT_LLM_BASE_URL = "https://openrouter.ai/api/v1";
    private static final String DEFAULT_HUB_BASE_URL = "https://hub.ag3nts.org";
    private static final String DEFAULT_DATA_DIR = "labs/data";

    public record Llm(String apiKey, String model, String baseUrl) implements LlmConfig {}

    public record Hub(String apiKey, String baseUrl, String verifyUrl) {}

    public static LabsConfig load() {
        Properties props;
        try (InputStream in = LabsConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in == null) throw new RuntimeException("application.properties not found on classpath");
            props = read(in);
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

        return new LabsConfig(llm, hub, Path.of(require(props, "labs.lessons.dir")), dataDir(props));
    }

    /**
     * Read as UTF-8 explicitly. {@link Properties#load(InputStream)} decodes ISO-8859-1 whatever
     * the file actually is, and {@code labs.lessons.dir} points at a path outside the repository —
     * one accented character in it comes back mangled, and {@code Lesson.of} then blames
     * {@code labs.lessons.dir} for a value that was correct all along.
     */
    static Properties read(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        return props;
    }

    /**
     * Absolutised, so it no longer matters which directory the process was launched from — the
     * relative path this replaces only resolved from the repository root.
     */
    static Path dataDir(Properties props) {
        return Path.of(orDefault(props, "labs.data.dir", DEFAULT_DATA_DIR)).toAbsolutePath().normalize();
    }

    /**
     * The three ways a human names a model for this machine, most specific first:
     * {@code -Dopenrouter.model} for one run, {@code OPENROUTER_MODEL} for one shell,
     * {@code openrouter.model} for this checkout.
     *
     * <p>Null when none of them does, which is the ordinary case — the runner's own pin then
     * fills the gap, through {@code LlmClient.defaultModel}. Nothing is invented here: a default
     * model would mean a typo'd override quietly runs something nobody chose, and a run against
     * the wrong model still answers and still costs.
     */
    private static String resolveModel(Properties props) {
        return firstPresent(
                System.getProperty("openrouter.model"),
                System.getenv("OPENROUTER_MODEL"),
                orDefault(props, "openrouter.model", null));
    }

    /** The first value that is actually set; null if none is. Blank counts as unset throughout. */
    static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
