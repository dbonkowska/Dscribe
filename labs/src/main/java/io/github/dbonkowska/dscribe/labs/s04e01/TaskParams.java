package io.github.dbonkowska.dscribe.labs.s04e01;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s04e01/task.properties}.
 *
 * <p>The code knows only that there is one endpoint taking an action and its parameters, a web
 * panel the records can be read from, and a pattern for the reply that carries the result. Which
 * actions exist is not here at all: the endpoint describes itself, and the model learns it from
 * that reply.
 *
 * <p>The panel's pages bind by name: {@code pages.front=/}, {@code pages.notes=/notes}. The order
 * of the file is the order the read tool offers them in.
 *
 * @param verifyTask   the task name the hub expects
 * @param flagPattern  a regex matching a reply that carries the result
 * @param action       the tool that sends one action
 * @param panelBaseUrl the panel's origin. The hub key is its access key, so this is where the key
 *                     goes: an {@code https} origin, stored without a trailing slash
 * @param login        the operator login the exercise hands over
 * @param password     the operator password, redacted from the transcript like the keys
 * @param pages        page name to a path on the panel. The model names a page; code supplies the
 *                     path, so each one has to be a plain path — see {@link #isPlainPath}
 * @param read         the tool that reads one page
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        ToolPrompt action,
        String panelBaseUrl,
        String login,
        String password,
        Map<String, String> pages,
        ToolPrompt read) {

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        require(verifyTask, "verifyTask");
        require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        requirePrompt(action, "action");
        require(panelBaseUrl, "panelBaseUrl");
        require(login, "login");
        require(password, "password");
        requirePrompt(read, "read");

        panelBaseUrl = origin(panelBaseUrl);

        // an empty enum is a read tool that can read nothing, and a run without reads can only guess
        if (pages == null || pages.isEmpty()) {
            throw new IllegalStateException(
                    "pages must name at least one page of the panel: without one the model cannot read"
                            + " a single id. Set pages.<name>=<path> in the lesson's task.properties.");
        }
        for (Map.Entry<String, String> page : pages.entrySet()) {
            if (!isPlainPath(page.getValue())) {
                throw new IllegalStateException(
                        "pages." + page.getKey() + " must be a path on the panel starting with a single /,"
                                + " not " + page.getValue() + ". It is appended to panelBaseUrl on a"
                                + " logged-in session, so anything else can send the request elsewhere."
                                + " Fix it in the lesson's task.properties.");
            }
        }
        // the binder already keeps file order; copied so the order cannot change under the enum
        pages = Collections.unmodifiableMap(new LinkedHashMap<>(pages));
    }

    /**
     * An {@code https} origin, returned without a trailing slash so that base + path has exactly
     * one. Anything else is refused: a plain {@code http} base would send the hub key unencrypted,
     * and a path on the base is a prefix the page-path check never sees.
     */
    private static String origin(String value) {
        URI uri;
        try {
            uri = URI.create(value.strip());
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        String path = uri == null ? null : uri.getRawPath();
        if (uri == null
                || !"https".equals(uri.getScheme())
                || uri.getHost() == null
                || (path != null && !path.isEmpty() && !path.equals("/"))
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "panelBaseUrl must be an https origin such as https://panel.example, not " + value
                            + ". The hub key is sent there as the panel's access key. Fix it in the"
                            + " lesson's task.properties.");
        }
        String strip = value.strip();
        return strip.endsWith("/") ? strip.substring(0, strip.length() - 1) : strip;
    }

    /**
     * Copied from s03e05's {@code HubPath} rather than imported: a type from a finished lesson would
     * freeze it. Without the leading slash, {@code @elsewhere/x} turns the base's host into user
     * info and sends the request to {@code elsewhere}. A scheme or a leading {@code //} would be
     * appended harmlessly, but either means an address was written where a path belongs. A path that
     * does not parse would fail at call time as a crash, so it is refused here instead.
     */
    private static boolean isPlainPath(String value) {
        if (value == null
                || !value.startsWith("/")
                || value.startsWith("//")
                || value.contains("://")) {
            return false;
        }
        try {
            URI.create("https://panel.invalid" + value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Checked here rather than in {@code ToolPrompt}, which cannot know which tool it belongs to,
     * and so could not name the key to edit.
     */
    private static void requirePrompt(ToolPrompt prompt, String key) {
        if (prompt == null) {
            throw new IllegalStateException(
                    key + " is missing: set " + key + ".name and " + key + ".description in the lesson's"
                            + " task.properties.");
        }
        require(prompt.name(), key + ".name");
        require(prompt.description(), key + ".description");
    }

    /**
     * Compiled here rather than where it is applied, so a typo fails before anything is spent: a
     * pattern that throws on the first reply would discard a result the hub had already given.
     */
    private static void compiles(String pattern, String key) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(
                    key + " is not a valid regex: " + e.getDescription() + ". Remember every backslash is"
                            + " doubled in a properties file.", e);
        }
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    key + " is missing or blank. A string key nobody wrote binds to null rather"
                            + " than failing, and this one would fail later and further from"
                            + " here. Set it in the lesson's task.properties.");
        }
    }

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {}
}
