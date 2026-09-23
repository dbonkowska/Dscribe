package io.github.dbonkowska.dscribe.labs.s03e04;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
 * @param replyMinBytes   the exercise's floor on a reply's UTF-8 byte length. Boxed: a primitive
 *                        would bind a missing key to 0 and pass every check below
 * @param replyMaxBytes   the exercise's ceiling on a reply's UTF-8 byte length. Boxed, as above
 * @param flagPattern     a regex matching the result the hub's check reports once it is ready
 */
public record TaskParams(
        String verifyTask,
        String toolPath,
        ToolPrompt tool,
        String citiesFile,
        String itemsFile,
        String connectionsFile,
        Integer replyMinBytes,
        Integer replyMaxBytes,
        String flagPattern
) {

    public TaskParams {
        require(verifyTask, "verifyTask");
        require(toolPath, "toolPath");
        requirePrompt(tool, "tool");
        require(citiesFile, "citiesFile");
        require(itemsFile, "itemsFile");
        require(connectionsFile, "connectionsFile");
        require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        requireNumber(replyMinBytes, "replyMinBytes");
        requireNumber(replyMaxBytes, "replyMaxBytes");

        if (replyMinBytes < 0) {
            throw new IllegalStateException(
                    "replyMinBytes (" + replyMinBytes + ") is negative. Fix it in the lesson's"
                            + " task.properties.");
        }
        if (replyMaxBytes < 1) {
            throw new IllegalStateException(
                    "replyMaxBytes (" + replyMaxBytes + ") is below 1, so no reply could ever fit."
                            + " Fix it in the lesson's task.properties.");
        }

        // a pair nothing could ever satisfy — every reply would violate one bound or the other,
        // and that is worth catching before the first request rather than on the first reply
        if (replyMinBytes > replyMaxBytes) {
            throw new IllegalStateException(
                    "replyMinBytes (" + replyMinBytes + ") is greater than replyMaxBytes ("
                            + replyMaxBytes + "). No reply could ever satisfy both at once. Fix"
                            + " the pair in the lesson's task.properties.");
        }

        // the fixed replies are the ones no request can avoid, so a range that cannot hold them
        // is refused here rather than on whichever request first needs one
        for (String fixed : List.of(ToolReply.NO_MATCH, ToolReply.TOO_MANY, ToolReply.LOOKUP_FAILED)) {
            int bytes = fixed.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < replyMinBytes || bytes > replyMaxBytes) {
                throw new IllegalStateException(
                        "A fixed reply (\"" + fixed + "\") is " + bytes + " bytes, outside"
                                + " replyMinBytes " + replyMinBytes + " to replyMaxBytes "
                                + replyMaxBytes + ". The runner sends it as it is, so widen the"
                                + " range in the lesson's task.properties.");
            }
        }
    }

    private static void requireNumber(Integer value, String key) {
        if (value == null) {
            throw new IllegalStateException(
                    key + " is missing or blank. A number nobody wrote binds to null here rather"
                            + " than 0, so it fails at this boundary and not on the first reply."
                            + " Set it in the lesson's task.properties.");
        }
    }

    /** Compiled here so a typo fails before anything is spent, as in {@code s03e03.TaskParams}. */
    private static void compiles(String pattern, String key) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(
                    key + " is not a valid regex: " + e.getDescription() + ". Remember every"
                            + " backslash is doubled in a properties file.", e);
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

    /**
     * The prompt-facing half of the tool. {@code name} is a label only: the hub's registration
     * carries the URL and the description, so the name appears in the transcript and nowhere the
     * exercise sees.
     */
    public record ToolPrompt(String name, String description) {}
}
