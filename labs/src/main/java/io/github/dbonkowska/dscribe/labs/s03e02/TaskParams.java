package io.github.dbonkowska.dscribe.labs.s03e02;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s03e02/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository. The code knows only that there
 * is a shell endpoint taking a command under some key, a set of roots the model must not touch, and
 * two families of refusal the environment answers with — one worth retrying, one caused by the
 * model's own previous command. The words that name them are the exercise's.
 *
 * <p>Lists bind by index: {@code forbidden.1}, {@code transientCodes.1}, and so on.
 *
 * @param verifyTask     the task name the hub expects
 * @param flagPattern    a regex matching the hub's reply when it accepts a submission
 * @param codePattern    a regex matching the code the environment prints when the work is done
 * @param answerKey      the key the code is sent under in the submission's answer object. Missing, it
 *                       would bind to null and every submission would reach the hub malformed
 * @param shell          where and how a command is posted
 * @param forbidden      roots a command must not address
 * @param transientCodes reply fragments meaning "try again shortly"
 * @param causedCodes    reply fragments meaning "your previous command caused this"
 * @param command        the tool that runs one command
 * @param submit         the tool that sends the code the replies produced
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String codePattern,
        String answerKey,
        Shell shell,
        List<String> forbidden,
        List<String> transientCodes,
        List<String> causedCodes,
        ToolPrompt command,
        ToolPrompt submit) {

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        // Each binds to null when missing and fails later: the task name at the hub as a malformed
        // submission, the patterns after the result was already earned.
        require(verifyTask, "verifyTask");
        require(flagPattern, "flagPattern");
        require(codePattern, "codePattern");
        require(answerKey, "answerKey");
        compiles(flagPattern, "flagPattern");
        compiles(codePattern, "codePattern");
        if (shell == null) {
            throw new IllegalStateException(
                    "shell is missing: set shell.path, shell.commandKey and shell.helpCommand in the"
                            + " lesson's task.properties.");
        }
        requirePrompt(command, "command");
        requirePrompt(submit, "submit");
        // The one list that cannot be empty: with no roots the guard is a no-op that reads as working.
        // A blank entry is worse, since it matches every command and the model is refused for nothing.
        if (forbidden == null || forbidden.isEmpty()) {
            throw new IllegalStateException(
                    "forbidden must list at least one root: an empty list makes the guard a no-op, and"
                            + " nothing in a run would say so. Set forbidden.1, ... in the lesson's"
                            + " task.properties.");
        }
        if (forbidden.stream().anyMatch(root -> root == null || root.isBlank())) {
            throw new IllegalStateException(
                    "forbidden holds a blank entry, which would match every command. Remove it or fill"
                            + " it in in the lesson's task.properties.");
        }
        forbidden = List.copyOf(forbidden);
        transientCodes = transientCodes == null ? List.of() : List.copyOf(transientCodes);
        causedCodes = causedCodes == null ? List.of() : List.copyOf(causedCodes);
        // A reply fragment in both lists would be classified by whichever branch runs first, so the
        // other kind of refusal would silently never be handled.
        for (String code : transientCodes) {
            if (causedCodes.contains(code)) {
                throw new IllegalStateException(
                        "transientCodes and causedCodes both contain " + code + ". Whichever is checked"
                                + " first would silently win. Keep it in one of them in the lesson's"
                                + " task.properties.");
            }
        }
    }

    /**
     * Checked here rather than in {@code ToolPrompt}, which cannot know whether it is the command or
     * the submit, and so could not name the key to edit.
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
     * pattern that throws on the first reply would discard a code the environment had already printed.
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

    /**
     * The endpoint commands are posted to.
     *
     * @param path        where the shell is posted to, under the hub's base URL
     * @param commandKey  the body key a command travels under
     * @param helpCommand the command whose reply describes the environment, seeded into the
     *                    conversation
     */
    public record Shell(String path, String commandKey, String helpCommand) {

        public Shell {
            require(path, "shell.path");
            require(commandKey, "shell.commandKey");
            require(helpCommand, "shell.helpCommand");
        }
    }

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {}
}
