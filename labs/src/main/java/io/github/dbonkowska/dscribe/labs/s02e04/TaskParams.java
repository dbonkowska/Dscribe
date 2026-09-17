package io.github.dbonkowska.dscribe.labs.s02e04;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s02e04/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository, and this lesson adds two things
 * no earlier one had: the actions a model may call on an API the exercise exposes, and the format
 * each answer field must have. Both describe the exercise — an action name or a field name typed
 * into a {@code .java} file would say what the task is about as surely as a file name would — so
 * the code knows only that there is an API with a path and some actions, and an answer made of
 * named fields.
 *
 * <p>Lists bind by index: {@code api.actions.1}, {@code fields.1.name}, {@code fields.1.pattern},
 * and so on.
 *
 * @param verifyTask  the task name the hub expects
 * @param flagPattern a regex matching the result, used both to spot it and to validate it
 * @param api         the API the model searches through
 * @param fields      what an answer is made of, in the order they are sent
 * @param call        the tool that makes one API call
 * @param submit      the tool that sends an answer. Its description carries one {@code %s}, filled
 *                    with each field's name and format
 * @param answer      the terminal tool: calling it ends the run
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        Api api,
        List<Field> fields,
        ToolPrompt call,
        ToolPrompt submit,
        ToolPrompt answer) {

    /**
     * Each check refuses at the binding boundary, before the transcript is even open: what they
     * prevent is not a crash where the mistake is, but a run that spends and then fails in a way that
     * reads as the model's fault.
     */
    public TaskParams {
        // Each of these binds to null when missing and fails later: the task name at the hub as a
        // malformed submission, the flag pattern after the result was already earned.
        require(verifyTask, "verifyTask");
        require(flagPattern, "flagPattern");
        if (api == null) {
            throw new IllegalStateException(
                    "api is missing: set api.path, api.resetAction, api.helpAction and api.actions.1, ..."
                            + " in the lesson's task.properties.");
        }

        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException(
                    "fields must list at least one answer field: with none, every submission is empty and"
                            + " refused for it. Set fields.1.name and fields.1.pattern, ... in the lesson's"
                            + " task.properties.");
        }
        // Two slots of one name cannot both be filled by one submission, so no submission would ever
        // be complete — and the refusal would reach the model, which cannot fix a file.
        Set<String> names = new HashSet<>();
        for (Field field : fields) {
            if (!names.add(field.name())) {
                throw new IllegalStateException(
                        "fields names " + field.name() + " twice. Each answer field must appear once.");
            }
        }
        fields = List.copyOf(fields);
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
     * The API the model calls through a single generic tool.
     *
     * @param path        where the API is posted to, under the hub's base URL
     * @param resetAction the action that resets the source's state — called by the runner at startup,
     *                    and never offered to the model
     * @param helpAction  the action whose reply describes the API, seeded into the conversation
     * @param actions     what the model may call; the schema's {@code enum}
     */
    public record Api(String path, String resetAction, String helpAction, List<String> actions) {

        /**
         * The reset action in the allowlist is the one mistake here that does harm rather than
         * wasting a run: the model would be offered the call that rewinds the source, and could make
         * it in the middle of searching, with nothing in the transcript saying why the source then
         * looked different.
         */
        public Api {
            require(path, "api.path");
            require(resetAction, "api.resetAction");
            require(helpAction, "api.helpAction");

            if (actions == null || actions.isEmpty()) {
                throw new IllegalStateException(
                        "api.actions must list the actions the model may call; it is empty, and an empty"
                                + " schema enum is one no value can satisfy — the tool would be offered and"
                                + " never callable. Set api.actions.1, api.actions.2, ... in the lesson's"
                                + " task.properties.");
            }
            if (actions.contains(resetAction)) {
                throw new IllegalStateException(
                        "api.actions contains " + resetAction + ", which is api.resetAction. The runner"
                                + " resets at startup; the model must never be able to. Remove it from"
                                + " api.actions.");
            }
            actions = List.copyOf(actions);
        }
    }

    /**
     * One field of an answer.
     *
     * @param name    the key the hub expects the value under — the exercise's word
     * @param pattern a regex a non-empty value must match in full
     */
    public record Field(String name, String pattern) {

        /** Compiled here rather than where it is used, so a typo fails before anything is spent. */
        public Field {
            require(name, "fields.N.name");
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalStateException(
                        "fields: " + name + " has no pattern. A blank one matches only the empty value,"
                                + " so every real value would be refused. Set its pattern in the lesson's"
                                + " task.properties.");
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException(
                        "fields: the pattern for " + name + " is not a valid regex: " + e.getDescription()
                                + ". Remember every backslash is doubled in a properties file.", e);
            }
        }
    }

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {}
}
