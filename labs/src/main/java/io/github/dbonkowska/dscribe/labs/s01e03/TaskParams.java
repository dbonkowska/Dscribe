package io.github.dbonkowska.dscribe.labs.s01e03;

/**
 * Bound from {@code {labs.lessons.dir}/s01e03/task.properties}.
 *
 * <p>Tool names and descriptions are read by the model, and the API path belongs to the
 * exercise's own service — all of it exercise-supplied, so all of it lives outside the
 * repository. What the tools *do* with those names is Java; what they are called is not.
 *
 * <p>Nothing here describes the redirect itself. Which package goes where, and what makes it
 * happen, are the persona's business and live in {@code system.md} — a runner that knew them
 * would be doing the exercise on the model's behalf.
 *
 * <p>No {@code verifyTask}: this lesson never calls {@code /verify}, so the bundle does not carry
 * the key. Its flag arrives inside the conversation and is read out of the transcript.
 *
 * @param packagesPath the endpoint under the hub's base URL that both tools POST to
 */
public record TaskParams(
        String packagesPath,
        PackageTool check,
        PackageTool redirect
) {

    /** One tool as the model meets it. The action it stands for is the runner's to supply. */
    public record PackageTool(String name, String description) {}
}
