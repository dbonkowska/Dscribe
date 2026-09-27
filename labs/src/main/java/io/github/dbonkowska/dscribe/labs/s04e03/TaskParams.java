package io.github.dbonkowska.dscribe.labs.s04e03;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s04e03/task.properties}.
 *
 * <p>The code knows only that there is one endpoint taking an action and its parameters, a pattern
 * for the reply that carries the result, and which action the model may not send. Which actions
 * exist is not here at all: the endpoint describes itself, and the model learns it from that reply.
 *
 * @param verifyTask  the task name the hub expects
 * @param flagPattern a regex matching a reply that carries the result
 * @param action      the tool that sends one action
 * @param resetAction the action the run sends once at startup to start from a clean environment, and
 *                    the one action the tool refuses the model: mid-run it would wipe everything
 *                    done so far. Stored stripped
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        ToolPrompt action,
        String resetAction) {

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        require(verifyTask, "verifyTask");
        require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        requirePrompt(action, "action");
        require(resetAction, "resetAction");

        resetAction = resetAction.strip();
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
