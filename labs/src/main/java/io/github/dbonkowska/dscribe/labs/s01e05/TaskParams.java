package io.github.dbonkowska.dscribe.labs.s01e05;

import java.util.List;

/**
 * Bound from {@code {labs.lessons.dir}/s01e05/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository, and this lesson stretches
 * what that covers: the rate-limit header names are here too. They are a fact about the
 * exercise's API rather than about this framework, and nothing documents them in advance — the
 * first real run is what settles them, which only works if changing them is an edit to a file
 * rather than to a class.
 *
 * <p>What is deliberately absent is everything the API documents about itself. The actions, their
 * parameters and their ordering are discovered at run time, so there is no key for them here and
 * no constant for them anywhere.
 *
 * @param verifyTask  the task name the hub expects
 * @param flagPattern a regex the reported result must match before the run accepts it
 * @param limits      which headers this API announces its request budget in
 * @param call        the tool the run talks to the API with
 * @param answer      the terminal tool: calling it ends the run
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        Limits limits,
        ToolPrompt call,
        ToolPrompt answer) {

    /**
     * Header names, most preferred first. Looked up case-insensitively, so they can be written
     * however the API documents them.
     *
     * <p>Either list may be empty, and empty means "this API says nothing of the sort" rather
     * than zero: an absent budget is never read as a spent one.
     *
     * @param resetHeaders     names that may carry when the budget refills
     * @param remainingHeaders names that may carry how much of it is left
     */
    public record Limits(List<String> resetHeaders, List<String> remainingHeaders) {

        /**
         * A list nobody wrote binds as null, and the difference between "no such key" and "no
         * such header" is one nothing downstream should have to know about. Same rule as blank
         * configuration everywhere else here: absent means absent, and absent means no waiting.
         */
        public Limits {
            resetHeaders = resetHeaders == null ? List.of() : List.copyOf(resetHeaders);
            remainingHeaders = remainingHeaders == null ? List.of() : List.copyOf(remainingHeaders);
        }
    }

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {}
}
