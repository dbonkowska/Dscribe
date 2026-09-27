package io.github.dbonkowska.dscribe.labs.s04e05;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s04e05/task.properties}.
 *
 * <p>The code knows that there is one endpoint taking a tool, an optional action and their
 * parameters; a file of needs to hand the model; and which calls the model may not make. What the
 * database holds, how its tables are shaped and which user creates an order are not here at all:
 * the model finds them out at run time. What is here are the names the guards match on, because a
 * guard has to recognise a call before it is sent.
 *
 * @param verifyTask  the task name the hub expects
 * @param flagPattern a regex matching a reply that carries the result
 * @param api         the one tool the model calls, as prompt surface
 * @param needs       where the needs file comes from
 * @param resetTool   the tool the run sends once at startup to restore the seeded state, and the
 *                    one tool refused to the model: mid-run it would wipe every order it created
 * @param orders      the orders tool: how the run reads the seeded orders, and which action on it
 *                    the model may not aim at one of them
 * @param database    the query tool, whose replies are checked for a possible cut-off
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        ToolPrompt api,
        Needs needs,
        String resetTool,
        Orders orders,
        Database database) {

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        verifyTask = require(verifyTask, "verifyTask");
        flagPattern = require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        requirePresent(api, "api", "api.name and api.description");
        requirePresent(needs, "needs", "needs.url and needs.file");
        resetTool = require(resetTool, "resetTool");
        requirePresent(orders, "orders", "orders.tool, orders.get, orders.delete and orders.list");
        requirePresent(database, "database", "database.tool");
    }

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {
        public ToolPrompt {
            name = require(name, "api.name");
            description = require(description, "api.description");
        }
    }

    public record Needs(String url, String file) {
        public Needs {
            url = require(url, "needs.url");
            file = require(file, "needs.file");
        }
    }

    /**
     * @param tool   the orders tool's name
     * @param get    the action that lists orders, sent by the run right after the reset
     * @param delete the action refused to the model when it names a seeded order
     * @param list   the field of the {@code get} reply that holds the orders
     */
    public record Orders(String tool, String get, String delete, String list) {
        public Orders {
            tool = require(tool, "orders.tool");
            get = require(get, "orders.get");
            delete = require(delete, "orders.delete");
            list = require(list, "orders.list");
        }
    }

    public record Database(String tool) {
        public Database {
            tool = require(tool, "database.tool");
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
