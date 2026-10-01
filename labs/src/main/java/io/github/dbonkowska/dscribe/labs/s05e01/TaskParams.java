package io.github.dbonkowska.dscribe.labs.s05e01;

import java.util.List;
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
 * @param endCode      the reply code meaning there is nothing more to listen to
 * @param noiseMarkers words that mark a transcription as radio noise, as an indexed list:
 *                     {@code noiseMarkers.1=…}. Used only to count noise in the log — nothing is
 *                     dropped by them
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        Actions actions,
        int endCode,
        List<String> noiseMarkers) {

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
        if (noiseMarkers == null || noiseMarkers.isEmpty()) {
            throw new IllegalStateException(
                    "noiseMarkers is missing: set noiseMarkers.1=… in the lesson's task.properties.");
        }
        noiseMarkers = List.copyOf(noiseMarkers);
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
