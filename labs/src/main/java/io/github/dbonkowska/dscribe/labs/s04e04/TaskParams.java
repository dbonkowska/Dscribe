package io.github.dbonkowska.dscribe.labs.s04e04;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s04e04/task.properties}.
 *
 * <p>The code knows that there is an archive of notes in three files, one of which is a list of
 * lines split by a separator; that the result is a tree of three directories; and which actions
 * build it. What the notes say, what the directories are called and what the actions are named
 * is all here, none of it in Java. The limits a name must meet are not here either: the hub
 * states them in its help reply, and the run reads them from there.
 *
 * @param verifyTask   the task name the hub expects
 * @param flagPattern  a regex matching a reply that carries the result
 * @param archive      where the notes come from
 * @param notes        which file in the archive holds which kind of note
 * @param transactions how a line of the fixed-format file is split
 * @param dirs         the three directory names, one per kind of entity
 * @param actions      the action names, as the hub's help lists them
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        Archive archive,
        Notes notes,
        Transactions transactions,
        Dirs dirs,
        Actions actions) {

    /** Each check refuses at the binding boundary, before the archive is fetched. */
    public TaskParams {
        verifyTask = require(verifyTask, "verifyTask");
        flagPattern = require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        requirePresent(archive, "archive", "archive.url and archive.file");
        requirePresent(notes, "notes", "notes.needs, notes.calls and notes.transactions");
        requirePresent(transactions, "transactions", "transactions.separator");
        requirePresent(dirs, "dirs", "dirs.cities, dirs.people and dirs.goods");
        requirePresent(actions, "actions",
                "actions.help, actions.reset, actions.createDirectory, actions.createFile and actions.done");
    }

    public record Archive(String url, String file) {
        public Archive {
            url = require(url, "archive.url");
            file = require(file, "archive.file");
        }
    }

    /** File names inside the archive. */
    public record Notes(String needs, String calls, String transactions) {
        public Notes {
            needs = require(needs, "notes.needs");
            calls = require(calls, "notes.calls");
            transactions = require(transactions, "notes.transactions");
        }
    }

    /**
     * Stored stripped, as every value is: a properties file drops a value's leading whitespace
     * anyway, so the parser strips each part rather than relying on spaces kept here.
     */
    public record Transactions(String separator) {
        public Transactions {
            separator = require(separator, "transactions.separator");
        }
    }

    public record Dirs(String cities, String people, String goods) {
        /**
         * The hub refuses a name used twice anywhere in the tree, so two kinds sharing a directory
         * would fail on the batch — after the model call was already paid for.
         */
        public Dirs {
            cities = require(cities, "dirs.cities");
            people = require(people, "dirs.people");
            goods = require(goods, "dirs.goods");
            distinct(cities, "dirs.cities", people, "dirs.people");
            distinct(cities, "dirs.cities", goods, "dirs.goods");
            distinct(people, "dirs.people", goods, "dirs.goods");
        }

        private static void distinct(String a, String aKey, String b, String bKey) {
            if (a.equals(b)) {
                throw new IllegalStateException(
                        aKey + " and " + bKey + " are both " + a + ". Each kind of entity needs a"
                                + " directory of its own: the hub refuses a name used twice.");
            }
        }
    }

    public record Actions(String help, String reset, String createDirectory, String createFile, String done) {
        public Actions {
            help = require(help, "actions.help");
            reset = require(reset, "actions.reset");
            createDirectory = require(createDirectory, "actions.createDirectory");
            createFile = require(createFile, "actions.createFile");
            done = require(done, "actions.done");
        }
    }

    /**
     * A nested group with none of its keys written binds to null rather than to a record, so its
     * own constructor never runs to name what is missing.
     */
    private static void requirePresent(Object group, String key, String keys) {
        if (group == null) {
            throw new IllegalStateException(
                    key + " is missing: set " + keys + " in the lesson's task.properties.");
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
