package io.github.dbonkowska.dscribe.labs.s05e01;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s05e01/task.properties}.
 *
 * <p>The code knows that a session is opened, drained one payload at a time until the hub says it
 * is done, and closed with one report. What the actions are called, which code means "done" and
 * which words mark noise are the exercise's, so they are here.
 *
 * @param verifyTask   the task name the hub expects
 * @param flagPattern  a regex matching a reply that carries the result
 * @param actions      the session's three actions
 * @param payloadCode  the reply code a listen carries a payload under. Any code that is neither
 *                     this nor {@code endCode} stops the run with the hub's words
 * @param endCode      the reply code meaning there is nothing more to listen to
 * @param noiseMarkers words that mark a transcription as radio noise, as an indexed list:
 *                     {@code noiseMarkers.1=…}. Used only to count noise in the log — nothing is
 *                     dropped by them
 * @param fields       what the report is made of, in the order it is sent, as an indexed list:
 *                     {@code fields.1.name}, {@code fields.1.format}. The names are the exercise's
 *                     words, so the extraction schema is built from them at run time
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        Actions actions,
        int payloadCode,
        int endCode,
        List<String> noiseMarkers,
        List<Field> fields) {

    /**
     * The key every call to the hub carries its action under. Protocol vocabulary, the same for any
     * task — and reserved, because the report's fields are merged into the same object.
     */
    static final String ACTION_KEY = "action";

    /** How a field is written on the way out — the only formats {@link Report#answer} knows. */
    static final List<String> FORMATS = List.of("text", "integer", "digits", "decimal2");

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        verifyTask = require(verifyTask, "verifyTask");
        flagPattern = require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        if (actions == null) {
            throw new IllegalStateException(
                    "actions is missing: set actions.start, actions.listen and actions.transmit in the"
                            + " lesson's task.properties.");
        }
        if (endCode <= 0) {
            // an int nobody wrote binds to 0, which no reply carries: the run would listen to its cap
            throw new IllegalStateException(
                    "endCode is " + endCode + ", which is missing or not a code the hub sends. Set it in"
                            + " the lesson's task.properties.");
        }
        if (payloadCode <= 0) {
            throw new IllegalStateException(
                    "payloadCode is " + payloadCode + ", which is missing or not a code the hub sends. Set"
                            + " it in the lesson's task.properties.");
        }
        if (payloadCode == endCode) {
            throw new IllegalStateException(
                    "payloadCode and endCode are both " + endCode + ". One code cannot mean both a payload"
                            + " and the end of the material.");
        }
        if (noiseMarkers == null || noiseMarkers.isEmpty()) {
            throw new IllegalStateException(
                    "noiseMarkers is missing: set noiseMarkers.1=… in the lesson's task.properties.");
        }
        noiseMarkers = List.copyOf(noiseMarkers);
        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException(
                    "fields must list at least one report field: with none there is nothing to ask the"
                            + " model for and nothing to send. Set fields.1.name and fields.1.format, ... in"
                            + " the lesson's task.properties.");
        }
        Set<String> seen = new HashSet<>();
        for (Field field : fields) {
            // refused by name before the runner merges: a field of this name would replace the
            // action without a word, and send an extracted value as it
            if (ACTION_KEY.equals(field.name())) {
                throw new IllegalStateException(
                        "fields names " + ACTION_KEY + ", which the run sets itself on every call. Remove"
                                + " it; the report's action comes from actions.transmit.");
            }
            if (!seen.add(field.name())) {
                throw new IllegalStateException(
                        "fields names " + field.name() + " twice. Each report field must appear once.");
            }
        }
        fields = List.copyOf(fields);
    }


    /**
     * One value the report carries, in the order it is sent.
     *
     * @param name   the hub's name for it, which is also the key the model fills
     * @param format how code writes it: {@code text} stripped, {@code integer} as a number,
     *               {@code digits} with every non-digit removed, {@code decimal2} rounded half up to
     *               exactly two places
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

    public record Actions(String start, String listen, String transmit) {
        public Actions {
            start = require(start, "actions.start");
            listen = require(listen, "actions.listen");
            transmit = require(transmit, "actions.transmit");
        }
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
