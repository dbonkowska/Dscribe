package io.github.dbonkowska.dscribe.labs.s01e02;

/**
 * Bound from {@code {labs.lessons.dir}/s01e02/task.properties}.
 *
 * <p>Tool names, tool descriptions and hub paths are all exercise-supplied — the model reads the
 * names and descriptions, and the paths belong to the exercise's API — so they live outside the
 * repository with the rest of the lesson's inputs. Only the argument field names are Java, being
 * structure rather than content.
 *
 * @param dataFile   the input to fetch from the hub
 * @param verifyTask the task name {@code hub.verify} expects
 * @param answer     the terminal tool: calling it ends the run and its arguments are the answer
 */
public record TaskParams(
        String dataFile,
        String verifyTask,
        HubTool sightings,
        HubTool accessLevel,
        AnswerSpec answer
) {

    /** @param path the hub endpoint the handler POSTs its arguments to */
    public record HubTool(String name, String description, String path) {}

    public record AnswerSpec(String name, String description) {}
}