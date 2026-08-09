package io.github.dbonkowska.dscribe.labs.s01e01;

import java.util.List;

/**
 * Bound from {@code {labs.lessons.dir}/s01e01/task.properties}.
 *
 * <p>These are the exercise's filter criteria and its tag vocabulary, so they live outside
 * the repository with the rest of the lesson's inputs rather than as constants in this
 * package.
 *
 * @param tags       the closed vocabulary offered to the model, as an indexed list:
 *                   {@code tags.1=…}, {@code tags.2=…}
 * @param selectTag  the tag a person must carry to make the answer
 * @param dataFile   the input to fetch from the hub
 * @param verifyTask the task name {@code hub.verify} expects
 */
public record TaskParams(
        String city,
        String gender,
        int referenceYear,
        int minAge,
        int maxAge,
        List<String> tags,
        String selectTag,
        String dataFile,
        String verifyTask
) {}