package io.github.dbonkowska.dscribe.labs.s04e03;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s04e03/task.properties}.
 *
 * <p>The code knows only that there is one endpoint taking an action and its parameters, a pattern
 * for the reply that carries the result, and which actions the model may not send. Which actions
 * exist is not here at all: the endpoint describes itself, and the model learns it from that reply.
 *
 * @param verifyTask     the task name the hub expects
 * @param flagPattern    a regex matching a reply that carries the result
 * @param action         the tool that sends one action
 * @param resetAction    the action the run sends once at startup to start from a clean environment.
 *                       It has to be one of {@code refusedActions}, so the model cannot send it too
 * @param refusedActions actions the tool refuses before sending. A reset mid-run would wipe
 *                       everything the model has learned about the environment
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        ToolPrompt action,
        String resetAction,
        List<String> refusedActions) {

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        require(verifyTask, "verifyTask");
        require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        requirePrompt(action, "action");
        require(resetAction, "resetAction");

        // an empty list lets the model send the reset, and the reset is why the list exists
        if (refusedActions == null || refusedActions.isEmpty()) {
            throw new IllegalStateException(
                    "refusedActions must name at least the reset action, or the model can wipe the"
                            + " environment mid-run. Set refusedActions.1=<action> in the lesson's"
                            + " task.properties.");
        }
        for (String refused : refusedActions) {
            require(refused, "refusedActions");
        }
        refusedActions = refusedActions.stream().map(String::strip).toList();
        resetAction = resetAction.strip();

        // otherwise the model could send the run's own reset under a name the list never mentions
        if (!refusedActions.contains(resetAction)) {
            throw new IllegalStateException(
                    "resetAction " + resetAction + " is not one of refusedActions " + refusedActions
                            + ". The run's reset has to be one the model is refused. Add it to"
                            + " refusedActions, or fix resetAction, in the lesson's task.properties.");
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
