package io.github.dbonkowska.dscribe.labs.config;

import io.github.dbonkowska.dscribe.config.LlmConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
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

    /**
     * @param visionModel the model for a call a runner delegates to a second model — null when
     *                    nothing configures one, exactly like {@code model}
     */
    public record Llm(String apiKey, String model, String visionModel, String baseUrl)
            implements LlmConfig {

        /**
         * The same credentials and endpoint, with the delegated slot's model in the model slot.
         *
         * <p>A second slot rather than an exception carved into {@code LlmClient.defaultModel}'s
         * precedence rule. The rule is that whatever the configuration names beats the caller's
         * pin, and every runner depends on it — so a delegated call that must not be retargeted
         * by {@code -Dopenrouter.model} gets its own key to be overridden by instead, and the
         * rule itself does not move.
         */
        public LlmConfig vision() {
            return new Slot(apiKey, visionModel, baseUrl);
        }
    }

    /** A view of one model slot as a configuration in its own right. */
    private record Slot(String apiKey, String model, String baseUrl) implements LlmConfig {}

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
                resolveModel(props, "openrouter.model"),
                resolveModel(props, "openrouter.vision.model"),
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
     * The three ways a human names a model for this machine, most specific first: a system
     * property for one run, an environment variable for one shell, the properties file for this
     * checkout.
     *
     * <p>Null when none of them does, which is the ordinary case — the runner's own pin then
     * fills the gap, through {@code LlmClient.defaultModel}. Nothing is invented here: a default
     * model would mean a typo'd override quietly runs something nobody chose, and a run against
     * the wrong model still answers and still costs.
     *
     * <p>Takes the key rather than hardcoding one, so a second model slot gets the same
     * precedence for free and the two cannot drift apart. Each slot is resolved from its own key
     * alone: an override meant for the planning model must not reach the delegated one, or a
     * flag intended to try a different planner silently sends the image somewhere new.
     */
    static String resolveModel(Properties props, String key) {
        return firstPresent(
                System.getProperty(key),
                System.getenv(environmentName(key)),
                orDefault(props, key, null));
    }

    /**
     * The environment variable a properties key corresponds to: dots to underscores, upper case.
     *
     * <p>{@link Locale#ROOT} named rather than defaulted. Under a Turkish locale the platform
     * default uppercases {@code "vision"} to {@code "VİSİON"}, and the variable is then never
     * found — silently, on someone else's machine, with the properties file quietly winning
     * instead.
     */
    static String environmentName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_');
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
