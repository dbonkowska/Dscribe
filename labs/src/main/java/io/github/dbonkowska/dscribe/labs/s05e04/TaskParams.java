package io.github.dbonkowska.dscribe.labs.s05e04;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s05e04/task.properties}.
 *
 * <p>The code knows that a game is a walk across a grid, that a status check gates every move, that
 * a free-text hint is labelled into a bearing, and that a refused step is cleared with a digest. What
 * the endpoints, commands, reply fields and markers are called is the exercise's, so it is here.
 *
 * @param verifyTask      the task name the hub expects
 * @param flagPattern     a regex matching a reply that carries the result
 * @param statusPath      the path read before every move, and posted to when a step is refused
 * @param hintPath        the path the free-text hint comes from
 * @param commandKey      the answer key every command is sent under
 * @param start           the command that opens a game
 * @param ahead           the command that moves one column on, staying in the row
 * @param left            the command that moves one column on and one row up
 * @param right           the command that moves one column on and one row down
 * @param rows            the grid's height. An {@code Integer}, so a key nobody wrote is refused
 *                        rather than bound to 0
 * @param columns         the grid's width, read the same way
 * @param clearMarker     the phrase a clear status reply carries, written plainly: it is compared on
 *                        letters alone
 * @param frequencyKey    the key a refused step's number is read and sent under
 * @param codeKey         the key a refused step's code is read under
 * @param hashKey         the key the digest is sent under
 * @param digestAlgorithm a {@link MessageDigest} algorithm name
 * @param digestSuffix    appended to the code before it is hashed
 * @param confusables     pairs of characters a garbled reply swaps, as an indexed list:
 *                        {@code confusables.1=ab}
 * @param replies         where each value sits in a reply, as JSON pointers, so no field name is in
 *                        Java
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String statusPath,
        String hintPath,
        String commandKey,
        String start,
        String ahead,
        String left,
        String right,
        Integer rows,
        Integer columns,
        String clearMarker,
        String frequencyKey,
        String codeKey,
        String hashKey,
        String digestAlgorithm,
        String digestSuffix,
        List<String> confusables,
        Replies replies) {

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        verifyTask = require(verifyTask, "verifyTask");
        flagPattern = require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        statusPath = require(statusPath, "statusPath");
        hintPath = require(hintPath, "hintPath");
        commandKey = require(commandKey, "commandKey");
        start = require(start, "start");
        ahead = require(ahead, "ahead");
        left = require(left, "left");
        right = require(right, "right");
        positive(rows, "rows");
        positive(columns, "columns");
        clearMarker = require(clearMarker, "clearMarker");
        frequencyKey = require(frequencyKey, "frequencyKey");
        codeKey = require(codeKey, "codeKey");
        hashKey = require(hashKey, "hashKey");
        digestAlgorithm = require(digestAlgorithm, "digestAlgorithm");
        computable(digestAlgorithm);
        digestSuffix = require(digestSuffix, "digestSuffix");
        confusables = pairs(confusables);
        replies = checked(replies);
    }

    /**
     * Where each value sits in a reply.
     *
     * @param row         the current row
     * @param column      the current column
     * @param targetRow   the row of the last column's goal, carried only by the reply that opens a game
     * @param obstacleRow the row blocked in the current column
     * @param ended       true when the reply says the game was lost
     * @param hint        the free text of a hint reply
     */
    public record Replies(
            String row, String column, String targetRow, String obstacleRow, String ended, String hint) {}

    /** Checked here, not in {@link Replies}: a missing block has no constructor to run. */
    private static Replies checked(Replies replies) {
        if (replies == null) {
            throw new IllegalStateException(
                    "replies is missing: set replies.row and the other pointers in the lesson's"
                            + " task.properties.");
        }
        return new Replies(
                pointer(replies.row(), "replies.row"),
                pointer(replies.column(), "replies.column"),
                pointer(replies.targetRow(), "replies.targetRow"),
                pointer(replies.obstacleRow(), "replies.obstacleRow"),
                pointer(replies.ended(), "replies.ended"),
                pointer(replies.hint(), "replies.hint"));
    }

    private static String pointer(String value, String key) {
        String pointer = require(value, key);
        if (!pointer.startsWith("/")) {
            throw new IllegalStateException(
                    key + " is " + pointer + ", which is not a JSON pointer. Write it from the root of the"
                            + " reply, starting with /.");
        }
        return pointer;
    }

    private static List<String> pairs(List<String> confusables) {
        if (confusables == null || confusables.isEmpty()) {
            throw new IllegalStateException(
                    "confusables must list at least one pair. Set confusables.1=ab, ... in the lesson's"
                            + " task.properties.");
        }
        List<String> pairs = confusables.stream().map(entry -> require(entry, "confusables.N")).toList();
        for (String pair : pairs) {
            if (pair.length() != 2) {
                throw new IllegalStateException(
                        "confusables has " + pair + ", which is not a pair. Each entry is the two characters"
                                + " a garbled reply swaps.");
            }
        }
        return pairs;
    }

    private static void positive(Integer value, String key) {
        if (value == null || value < 1) {
            throw new IllegalStateException(
                    key + " is " + value + ". It sizes the grid every move is checked against, so it must"
                            + " be at least 1; set it in the lesson's task.properties.");
        }
    }

    /** Refused here, not on the first refused step, where a typo would cost the game in play. */
    private static void computable(String algorithm) {
        try {
            MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "digestAlgorithm is " + algorithm + ", which this JDK cannot compute.", e);
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
