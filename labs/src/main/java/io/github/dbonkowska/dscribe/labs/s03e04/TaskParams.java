package io.github.dbonkowska.dscribe.labs.s03e04;

/**
 * Bound from {@code {labs.lessons.dir}/s03e04/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository — the tool's prompt-facing
 * name and description, the endpoint path it answers on, the three catalog files' names, the
 * byte range a reply must fall inside, and the task name the hub expects. The catalog files
 * themselves live in the same lesson bundle, read as plain text via {@code Lesson.prompt}.
 *
 * <p>Follows {@code s03e03.TaskParams}'s shape: every check refuses at the binding boundary,
 * before the transcript is even open, naming the {@code task.properties} key to fix.
 *
 * @param verifyTask      the task name the hub expects
 * @param toolPath        the endpoint path the registered tool answers on
 * @param tool            the tool's prompt-facing name and description
 * @param citiesFile      the bundle file holding the cities catalog
 * @param itemsFile       the bundle file holding the items catalog
 * @param connectionsFile the bundle file joining item codes to city codes
 * @param replyMinBytes   the exercise's floor on a reply's UTF-8 byte length
 * @param replyMaxBytes   the exercise's ceiling on a reply's UTF-8 byte length
 */
public record TaskParams(
        String verifyTask,
        String toolPath,
        ToolPrompt tool,
        String citiesFile,
        String itemsFile,
        String connectionsFile,
        int replyMinBytes,
        int replyMaxBytes
) {

    public TaskParams {
        require(verifyTask, "verifyTask");
        require(toolPath, "toolPath");
        requirePrompt(tool, "tool");
        require(citiesFile, "citiesFile");
        require(itemsFile, "itemsFile");
        require(connectionsFile, "connectionsFile");

        // a pair nothing could ever satisfy — every reply would violate one bound or the other,
        // and that is worth catching before the first request rather than on the first reply
        if (replyMinBytes > replyMaxBytes) {
            throw new IllegalStateException(
                    "replyMinBytes (" + replyMinBytes + ") is greater than replyMaxBytes ("
                            + replyMaxBytes + "). No reply could ever satisfy both at once. Fix"
                            + " the pair in the lesson's task.properties.");
        }
    }

    /** Checked here rather than in {@code ToolPrompt}, which cannot know which tool it belongs to. */
    private static void requirePrompt(ToolPrompt prompt, String key) {
        if (prompt == null) {
            throw new IllegalStateException(
                    key + " is missing: set " + key + ".name and " + key + ".description in the"
                            + " lesson's task.properties.");
        }
        require(prompt.name(), key + ".name");
        require(prompt.description(), key + ".description");
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    key + " is missing or blank. A string key nobody wrote binds to null rather"
                            + " than failing, and this one would fail later and further from"
                            + " here. Set it in the lesson's task.properties.");
        }
    }

    /** The prompt-facing half of the tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {}
}
