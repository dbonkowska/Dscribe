package io.github.dbonkowska.dscribe.labs.s05e03;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from the s05e03 lesson bundle's {@code task.properties}.
 *
 * <p>The code knows that there is a shell to explore through, and a report whose values are printed
 * on the server as the answer, one of them a date that code shifts. Where the data lies, by how much
 * the date moves, what the answer's fields are called and what the tools are called are the
 * exercise's, so they are here.
 *
 * @param verifyTask    the task name the hub expects
 * @param flagPattern   a regex matching a reply that carries the result
 * @param dataDir       the absolute path the data lies under, handed to the model in {@code user.md}
 * @param dateShiftDays how many days code moves the reported date before it is sent, negative for
 *                      earlier. An {@code Integer}, so a key nobody wrote is refused rather than bound
 *                      to 0
 * @param fields        what the answer is made of, in the order it is sent, as an indexed list:
 *                      {@code fields.1.name}, {@code fields.1.format}. Exactly one is a {@code date}
 * @param shell         the shell tool's name and description, which are prompt surface
 * @param report        the report tool's name and description
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String dataDir,
        Integer dateShiftDays,
        List<Field> fields,
        Named shell,
        Named report) {

    /**
     * The key the hub reads a command under. Protocol vocabulary, the same for any task — and
     * reserved, because the report's fields are written into a JSON the run sends under this key.
     */
    static final String CMD_KEY = "cmd";

    /** The one format code changes on the way out. */
    static final String DATE = "date";

    /** How a field is written on the way out — the only formats the report knows. */
    static final List<String> FORMATS = List.of("text", "decimal", DATE);

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        verifyTask = require(verifyTask, "verifyTask");
        flagPattern = require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        dataDir = require(dataDir, "dataDir");
        if (!dataDir.startsWith("/")) {
            throw new IllegalStateException(
                    "dataDir is " + dataDir + ", which is not an absolute path. The model is told to look"
                            + " there, so it must not depend on a working directory nobody chose.");
        }
        if (dateShiftDays == null) {
            throw new IllegalStateException(
                    "dateShiftDays is missing. Unset it would read as 0 and send the reported date"
                            + " unshifted; set it in the lesson's task.properties, negative for earlier.");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException(
                    "fields must list at least one answer field. Set fields.1.name and fields.1.format,"
                            + " ... in the lesson's task.properties.");
        }
        Set<String> seen = new HashSet<>();
        List<String> dates = fields.stream().filter(field -> DATE.equals(field.format())).map(Field::name).toList();
        for (Field field : fields) {
            // refused by name: the answer is sent as a command under this key, and a field named like it
            // invites reading the answer as the command
            if (CMD_KEY.equals(field.name())) {
                throw new IllegalStateException(
                        "fields names " + CMD_KEY + ", which is the key the run sends every command under."
                                + " Rename the field.");
            }
            if (!seen.add(field.name())) {
                throw new IllegalStateException(
                        "fields names " + field.name() + " twice. Each answer field must appear once.");
            }
        }
        if (dates.size() != 1) {
            throw new IllegalStateException(
                    "fields must have exactly one field with format " + DATE + ", the one dateShiftDays"
                            + " moves; found " + dates + ".");
        }
        fields = List.copyOf(fields);
        shell = named(shell, "shell");
        report = named(report, "report");
    }

    /**
     * One value the answer carries, in the order it is sent.
     *
     * @param name   the hub's name for it, which is also the key the model fills
     * @param format how code writes it: {@code text} stripped, {@code decimal} as a JSON number exactly
     *               as given, {@code date} parsed as ISO, shifted and written ISO
     */
    public record Field(String name, String format) {
        public Field {
            name = require(name, "fields.N.name");
            format = require(format, "fields.N.format");
            if (!FORMATS.contains(format)) {
                throw new IllegalStateException(
                        "fields: " + name + " has format " + format + ", which the report cannot write. Use"
                                + " one of " + FORMATS + ".");
            }
        }
    }

    /** A tool's name and description, both prompt surface. */
    public record Named(String name, String description) {}

    /** Checked here, not in {@link Named}: only this level knows which key a tool was bound under. */
    private static Named named(Named tool, String key) {
        if (tool == null) {
            throw new IllegalStateException(
                    key + " is missing: set " + key + ".name and " + key + ".description in the lesson's"
                            + " task.properties.");
        }
        return new Named(require(tool.name(), key + ".name"), require(tool.description(), key + ".description"));
    }

    /**
     * Compiled here rather than where it is applied, so a typo fails before anything is spent: a
     * pattern that throws on the final reply would discard a result the hub had already given.
     */
    private static void compiles(String pattern, String key) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(
                    key + " is not a valid regex: " + e.getDescription() + ". Remember every backslash is"
                            + " doubled in a properties file.", e);
        }
    }

    private static String require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    key + " is missing or blank. A string key nobody wrote binds to null rather"
                            + " than failing, and this one would fail later and further from"
                            + " here. Set it in the lesson's task.properties.");
        }
        return value.strip();
    }
}
