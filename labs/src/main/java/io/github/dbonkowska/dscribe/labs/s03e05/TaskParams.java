package io.github.dbonkowska.dscribe.labs.s03e05;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s03e05/task.properties}.
 *
 * <p>The code knows only that there is one tool to start from, that whatever it finds is called by
 * name, and that an answer goes to verify. Where the search lives, what the tools are called and
 * how they are described to the model are the exercise's.
 *
 * @param verifyTask  the task name the hub expects
 * @param searchPath  where the one tool known up front lives, as a path on the hub. The hub key is
 *                    sent there, so it has to be a plain path — see {@link HubPath}
 * @param flagPattern a regex matching a verify reply that carries the result
 * @param search      the tool that finds other tools
 * @param call        the tool that calls a found tool by name
 * @param submit      the tool that sends an answer to verify
 */
public record TaskParams(
        String verifyTask,
        String searchPath,
        String flagPattern,
        ToolPrompt search,
        ToolPrompt call,
        ToolPrompt submit) {

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        require(verifyTask, "verifyTask");
        require(searchPath, "searchPath");
        require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        requirePrompt(search, "search");
        requirePrompt(call, "call");
        requirePrompt(submit, "submit");

        if (!HubPath.isPlain(searchPath)) {
            throw new IllegalStateException(
                    "searchPath must be a path on the hub starting with a single /, not " + searchPath
                            + ". It is appended to the hub's base URL with the hub key in the body, so"
                            + " anything else can send the key to another host. Fix it in the"
                            + " lesson's task.properties.");
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
