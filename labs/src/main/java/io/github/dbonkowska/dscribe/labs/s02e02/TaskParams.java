package io.github.dbonkowska.dscribe.labs.s02e02;

import java.util.List;

/**
 * Bound from {@code {labs.lessons.dir}/s02e02/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository. Here that includes two things
 * the earlier lessons had no equivalent of: the addresses a move may name, which become the
 * schema's vocabulary, and the field name the hub expects an address under. A closed vocabulary
 * is a {@code List<String>} plus a schema {@code enum}, never a Java enum — the constraint
 * survives, the constant does not.
 *
 * <p>Lists bind by index: {@code positions.1}, {@code positions.2}, and so on.
 *
 * @param verifyTask  the task name the hub expects
 * @param flagPattern a regex matching the result, used both to spot it and to validate it
 * @param dataFile    the artefact to look at, read fresh on every call
 * @param mediaType   what those bytes are, for the provider
 * @param targetImage a static image of the state being worked towards, shown beside the current
 *                    one. Optional: leave it unset and the target is whatever the delegated
 *                    prompt describes in words, which is cheaper and easier for a model to read
 * @param resetQuery  the query string that restores the artefact's starting state
 * @param positions   the addresses that exist
 * @param maxMoves    how many calls a run may spend moving
 * @param moveKey     the field name the hub wants an address under
 * @param vision      the tool the delegated read runs behind
 * @param move        the tool one move runs behind
 * @param answer      the terminal tool: calling it ends the run
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String dataFile,
        String mediaType,
        String targetImage,
        String resetQuery,
        List<String> positions,
        int maxMoves,
        String moveKey,
        ToolPrompt vision,
        ToolPrompt move,
        ToolPrompt answer) {

    /**
     * Both checks refuse at the binding boundary, for the reason s02e01's cap does: what they
     * prevent is not a crash but an unsatisfiable loop that reads as the model's own fault.
     *
     * <p>{@code maxMoves} is a primitive, so a key nobody wrote binds to zero rather than
     * failing. Every move is then refused for having no budget — as a tool result, which the
     * model reads as a limit it has already hit, so it reports failure on a run that never sent
     * anything. {@code positions} bind to null, and an empty {@code enum} is a schema no value
     * can satisfy: the provider can never emit a legal call, so the tool is offered and is
     * uncallable, forever.
     *
     * <p>Neither says anything an operator could act on from inside the run. Throwing here puts
     * the message before the transcript is even open, where whoever can fix it will read it.
     */
    public TaskParams {
        if (maxMoves <= 0) {
            throw new IllegalStateException(
                    "maxMoves must be a positive number of calls, not " + maxMoves
                            + " — a key that was never written binds to 0, and every move is then"
                            + " refused for having no budget. Set it in the lesson's"
                            + " task.properties.");
        }
        if (positions == null || positions.isEmpty()) {
            throw new IllegalStateException(
                    "positions must list the addresses a move may name; it is empty, and an empty"
                            + " schema enum is one no value can satisfy — the tool would be"
                            + " offered and never callable. Set positions.1, positions.2, ... in"
                            + " the lesson's task.properties.");
        }
        positions = List.copyOf(positions);

        // absent and present-but-empty mean the same thing: no target image, use the prompt's
        // description. Left as a blank string it would be sent as an image URL of "".
        targetImage = targetImage == null || targetImage.isBlank() ? null : targetImage;
    }

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {}
}
