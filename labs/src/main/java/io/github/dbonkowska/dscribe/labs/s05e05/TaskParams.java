package io.github.dbonkowska.dscribe.labs.s05e05;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s05e05/task.properties}.
 *
 * <p>The code knows that a device is driven through a route of jumps, each set partly over the API
 * and partly by a person, that a ratio is a weighted sum of the date taken modulo a number, and that
 * the device works in phases told apart by year. What the actions, sent names, weights, dates, bounds
 * and operator texts are is the exercise's, so it is here. Names the run only reads off a reply stay
 * in Java.
 *
 * @param verifyTask        the task name the hub expects
 * @param flagPattern       a regex matching a reply that carries the result
 * @param configureAction   the action that sets one value
 * @param getConfigAction   the action that reads the whole state
 * @param paramKey          the answer key a value's name is sent under
 * @param valueKey          the answer key the value itself is sent under
 * @param params            the names each settable value is sent as
 * @param sync              the ratio's weights and modulus
 * @param phaseBounds       the highest year each phase but the last serves, as an indexed list:
 *                          {@code phaseBounds.1=...}. Strictly increasing
 * @param fetchDate         the date the first stage jumps to and the second returns from, ISO
 * @param meetingDate       the date the last stage opens the way to, ISO
 * @param tunnelBattery     the charge the last stage needs, in the units the device counts
 * @param docsFile          the saved docs, in the lesson's bundle, holding the year/level table
 * @param zone              where "today" is read, since the device's date is a local one
 * @param stabilizationMin  the lowest stabilization the device takes
 * @param stabilizationMax  the highest
 * @param labels            what the two manual direction controls are called, as shown to the operator
 * @param say               what the operator is told, one text per instruction
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String configureAction,
        String getConfigAction,
        String paramKey,
        String valueKey,
        Params params,
        Sync sync,
        List<Integer> phaseBounds,
        LocalDate fetchDate,
        LocalDate meetingDate,
        Integer tunnelBattery,
        String docsFile,
        ZoneId zone,
        Integer stabilizationMin,
        Integer stabilizationMax,
        Labels labels,
        Say say) {

    /** Two decimal places hold 0.00 to 1.00, so no remainder above this can be sent. */
    static final int MAX_MODULUS = 101;

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        verifyTask = require(verifyTask, "verifyTask");
        flagPattern = require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        configureAction = require(configureAction, "configureAction");
        getConfigAction = require(getConfigAction, "getConfigAction");
        paramKey = require(paramKey, "paramKey");
        valueKey = require(valueKey, "valueKey");
        params = checked(params);
        sync = checked(sync);
        phaseBounds = increasing(phaseBounds);
        present(fetchDate, "fetchDate");
        present(meetingDate, "meetingDate");
        present(tunnelBattery, "tunnelBattery");
        if (tunnelBattery < 1) {
            throw new IllegalStateException(
                    "tunnelBattery is " + tunnelBattery + ". A stage that needs no charge would open on an"
                            + " empty device; set it to at least 1.");
        }
        docsFile = require(docsFile, "docsFile");
        present(zone, "zone");
        present(stabilizationMin, "stabilizationMin");
        present(stabilizationMax, "stabilizationMax");
        if (stabilizationMin > stabilizationMax) {
            throw new IllegalStateException(
                    "stabilizationMin is " + stabilizationMin + " and stabilizationMax is " + stabilizationMax
                            + ", so no value is in range. Every reading would count as failed.");
        }
        labels = checked(labels);
        say = checked(say);
    }

    /** The names each settable value is sent as, under {@link #paramKey}. */
    public record Params(String day, String month, String year, String syncRatio, String stabilization) {}

    /** The ratio: each weight times its part of the date, summed, modulo {@code modulus}. */
    public record Sync(Integer dayWeight, Integer monthWeight, Integer yearWeight, Integer modulus) {}

    /** What the control for each direction is called, as the operator sees it. */
    public record Labels(String past, String future) {}

    /**
     * What the operator is told. Slots are {@code %s}, or {@code %1$s} by position.
     *
     * @param standby   switch to the mode the API can write in
     * @param active    switch to the mode a jump needs
     * @param switchOn  turn a control on; one slot, the control's label
     * @param switchOff turn a control off; one slot, the control's label
     * @param level     set the power level; one slot, the level
     * @param go        the cue: everything holds, start the jump now
     * @param waitPhase the cue withdrawn; two slots, the phase wanted and the phase now
     * @param browser   the last word: the result shows in the browser, not here
     */
    public record Say(
            String standby, String active, String switchOn, String switchOff, String level, String go,
            String waitPhase, String browser) {}

    private static Params checked(Params params) {
        missing(params, "params", "params.day and the other sent names");
        return new Params(
                require(params.day(), "params.day"),
                require(params.month(), "params.month"),
                require(params.year(), "params.year"),
                require(params.syncRatio(), "params.syncRatio"),
                require(params.stabilization(), "params.stabilization"));
    }

    private static Sync checked(Sync sync) {
        missing(sync, "sync", "sync.dayWeight, sync.monthWeight, sync.yearWeight and sync.modulus");
        present(sync.dayWeight(), "sync.dayWeight");
        present(sync.monthWeight(), "sync.monthWeight");
        present(sync.yearWeight(), "sync.yearWeight");
        present(sync.modulus(), "sync.modulus");
        if (sync.modulus() < 1 || sync.modulus() > MAX_MODULUS) {
            throw new IllegalStateException(
                    "sync.modulus is " + sync.modulus() + ". The ratio is sent with two decimal places,"
                            + " which hold 0.00 to 1.00, so it must be from 1 to " + MAX_MODULUS + ".");
        }
        return sync;
    }

    private static Labels checked(Labels labels) {
        missing(labels, "labels", "labels.past and labels.future");
        return new Labels(require(labels.past(), "labels.past"), require(labels.future(), "labels.future"));
    }

    private static Say checked(Say say) {
        missing(say, "say", "say.standby and the other operator texts");
        return new Say(
                renders(say.standby(), "say.standby", 0),
                renders(say.active(), "say.active", 0),
                renders(say.switchOn(), "say.switchOn", 1),
                renders(say.switchOff(), "say.switchOff", 1),
                renders(say.level(), "say.level", 1),
                renders(say.go(), "say.go", 0),
                renders(say.waitPhase(), "say.waitPhase", 2),
                renders(say.browser(), "say.browser", 0));
    }

    /**
     * Rendered with markers and checked for each one, rather than by counting placeholders: {@code %%s}
     * looks like a slot and renders none, and {@code %1$s} twice is two placeholders and one slot.
     */
    private static String renders(String text, String key, int slots) {
        String value = require(text, key);
        Object[] markers = new Object[slots];
        for (int i = 0; i < slots; i++) {
            markers[i] = "<<slot-" + (i + 1) + ">>";
        }
        String rendered;
        try {
            rendered = value.formatted(markers);
        } catch (IllegalFormatException e) {
            throw new IllegalStateException(
                    key + " is " + value + ", which does not render with " + slots + " value(s): " + e
                            + ". A literal percent sign is written %%.", e);
        }
        for (Object marker : markers) {
            if (!rendered.contains((String) marker)) {
                throw new IllegalStateException(
                        key + " is " + value + ", which drops a value: it needs " + slots + " slot(s), %s or"
                                + " %1$s by position, and renders as " + rendered + ".");
            }
        }
        return value;
    }

    private static List<Integer> increasing(List<Integer> bounds) {
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalStateException(
                    "phaseBounds must list at least one bound. Set phaseBounds.1, ... in the lesson's"
                            + " task.properties, one per phase but the last.");
        }
        for (int i = 0; i < bounds.size(); i++) {
            present(bounds.get(i), "phaseBounds." + (i + 1));
            if (i > 0 && bounds.get(i) <= bounds.get(i - 1)) {
                throw new IllegalStateException(
                        "phaseBounds is " + bounds + ", which does not strictly increase. A year would fall"
                                + " in two phases, or in none.");
            }
        }
        return List.copyOf(bounds);
    }

    private static void missing(Object block, String key, String keys) {
        if (block == null) {
            throw new IllegalStateException(
                    key + " is missing: set " + keys + " in the lesson's task.properties.");
        }
    }

    /** A number or date nobody wrote binds to null; it is refused rather than read as a default. */
    private static void present(Object value, String key) {
        if (value == null) {
            throw new IllegalStateException(
                    key + " is missing. Set it in the lesson's task.properties.");
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
