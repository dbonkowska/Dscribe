package io.github.dbonkowska.dscribe.labs.s01e02;

/**
 * Bound from {@code {labs.lessons.dir}/s01e02/task.properties}.
 *
 * <p>Tool names, tool descriptions and hub paths are all exercise-supplied — the model reads the
 * names and descriptions, and the paths belong to the exercise's API — so they live outside the
 * repository with the rest of the lesson's inputs. Only the argument field names are Java, being
 * structure rather than content.
 *
 * @param dataFile          the input to fetch from the hub
 * @param verifyTask        the task name {@code hub.verify} expects
 * @param answerVocabulary  where inside {@code dataFile} the answer's allowed values sit
 * @param answer            the terminal tool: calling it ends the run and its arguments are the
 *                          answer
 */
public record TaskParams(
        String dataFile,
        String verifyTask,
        HubTool sightings,
        HubTool accessLevel,
        Vocabulary answerVocabulary,
        AnswerSpec answer
) {

    /** @param path the hub endpoint the handler POSTs its arguments to */
    public record HubTool(String name, String description, String path) {}

    /**
     * The values one answer field may take, read out of the exercise's own data file. Both halves
     * describe the shape of that file, so both belong with it rather than in source.
     *
     * @param pointer JSON pointer to the array the values sit in
     * @param key     the field of each entry holding the value
     */
    public record Vocabulary(String pointer, String key) {}

    public record AnswerSpec(String name, String description) {}
}
