package io.github.dbonkowska.dscribe.labs.s02e01;

import java.util.List;

/**
 * Bound from {@code {labs.lessons.dir}/s02e01/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository, and this lesson stretches that
 * further than the previous ones did. It is not only the scalar criteria: the file the rows come
 * from, the columns they arrive in, the placeholders the prompt is written around, the size it has
 * to fit, and the words the judge answers in are all facts about the exercise rather than about
 * this framework. A column name typed into a {@code .java} file is task content just as surely as
 * a city name would be.
 *
 * <p>The two patterns are here for a second reason as well: nothing documents the judge's wording
 * in advance, so the first real run is what settles them — which only works if changing them is an
 * edit to a file rather than to a class.
 *
 * @param verifyTask     the task name the hub expects
 * @param flagPattern    a regex matching the result, used both to spot it and to validate it
 * @param failurePattern a regex identifying a response as an objection, which ends a cycle
 * @param resetPrompt    the submission that clears the judge's counter
 * @param data           where the rows come from and what they are called
 * @param prompt         the shape the candidate has to take, and how large it may be
 * @param limits         which headers this API announces its request budget in
 * @param cycle          the tool one evaluation cycle runs behind
 * @param answer         the terminal tool: calling it ends the run
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String failurePattern,
        String resetPrompt,
        Data data,
        Prompt prompt,
        Limits limits,
        ToolPrompt cycle,
        ToolPrompt answer) {

    /**
     * The input file and the two columns read out of it.
     *
     * @param file              the hub data file, fetched fresh inside every cycle
     * @param idColumn          the column naming a row
     * @param descriptionColumn the column the candidate is asked to judge
     */
    public record Data(String file, String idColumn, String descriptionColumn) {}

    /**
     * What the candidate must contain and how big it may get.
     *
     * @param idPlaceholder          replaced with a row's id before sending
     * @param descriptionPlaceholder replaced with a row's description before sending
     * @param cap                    the judge's limit, in tokens, on a rendered prompt
     * @param margin                 tokens held back from the cap, absorbing the difference
     *                               between our encoding and the judge's
     */
    public record Prompt(String idPlaceholder, String descriptionPlaceholder, int cap, int margin) {}

    /**
     * Header names, most preferred first. Looked up case-insensitively, so they can be written
     * however the API documents them.
     *
     * @param resetHeaders names that may carry when the budget refills
     */
    public record Limits(List<String> resetHeaders) {

        /**
         * A list nobody wrote binds as null, and absent has to mean absent rather than zero — an
         * unannounced budget is never read as a spent one.
         */
        public Limits {
            resetHeaders = resetHeaders == null ? List.of() : List.copyOf(resetHeaders);
        }
    }

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {}
}
