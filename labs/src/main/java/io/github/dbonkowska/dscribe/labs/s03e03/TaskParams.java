package io.github.dbonkowska.dscribe.labs.s03e03;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s03e03/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository. The code knows only that there
 * is an endpoint taking one command from a closed set under some key, and two patterns that end a
 * run: one for the result, one for a failure. The words that fill them are the exercise's.
 *
 * <p>The list binds by index: {@code commands.1}, {@code commands.2}, and so on.
 *
 * <p>Where both patterns could match one reply, the result wins. Nothing here can check that,
 * since it needs real replies, so the runner fixes the order instead.
 *
 * @param verifyTask   the task name the hub expects
 * @param commandKey   the key a command is sent under in the answer object. Missing, it would bind
 *                     to null and every command would reach the hub malformed
 * @param flagPattern  a regex matching a reply that carries the result
 * @param crashPattern a regex matching a reply that says the run failed and cannot go on
 * @param commands     the closed set the model may send, which becomes the schema's {@code enum}
 * @param command      the tool that sends one command
 */
public record TaskParams(
        String verifyTask,
        String commandKey,
        String flagPattern,
        String crashPattern,
        List<String> commands,
        ToolPrompt command) {

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        // Each binds to null when missing and fails later: the task name at the hub as a malformed
        // command, the patterns after the result was already earned.
        require(verifyTask, "verifyTask");
        require(commandKey, "commandKey");
        require(flagPattern, "flagPattern");
        require(crashPattern, "crashPattern");
        compiles(flagPattern, "flagPattern");
        compiles(crashPattern, "crashPattern");
        requirePrompt(command, "command");

        // The one list that cannot be empty: an empty enum is a tool that can send nothing, and the
        // run would spend a model call finding that out.
        if (commands == null || commands.isEmpty()) {
            throw new IllegalStateException(
                    "commands must list at least one command: an empty set sends nothing, and the"
                            + " model would be offered no value to send. Set commands.1, ... in the"
                            + " lesson's task.properties.");
        }
        Set<String> seen = new HashSet<>();
        for (String entry : commands) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalStateException(
                        "commands holds a blank entry, which would put an empty string into the"
                                + " schema's enum. Remove it or fill it in in the lesson's"
                                + " task.properties.");
            }
            if (!seen.add(entry.trim())) {
                throw new IllegalStateException(
                        "commands lists " + entry.trim() + " twice. It would mean the same as the"
                                + " entry before it and make the schema's enum ambiguous. Keep one in"
                                + " the lesson's task.properties.");
            }
        }
        // stored as compared: an entry the checks above trimmed must not reach the schema padded
        commands = commands.stream().map(String::trim).toList();
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
     * pattern that throws on the first reply would discard a result the environment had already
     * given.
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
