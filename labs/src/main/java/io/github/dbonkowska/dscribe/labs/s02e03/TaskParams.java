package io.github.dbonkowska.dscribe.labs.s02e03;

import io.github.dbonkowska.dscribe.labs.tokens.TokenBudget;

import java.util.IllegalFormatException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s02e03/task.properties}.
 *
 * <p>Everything the exercise supplies lives outside the repository, and this lesson adds two things
 * no earlier one had: the shape of a source line and the shape of a submitted one. Both are facts
 * about the exercise — a severity word or a bracket typed into a {@code .java} file would describe
 * the task as surely as a file name would — so the code knows only that a line has a date, a time,
 * a severity and a message, and never how they are written.
 *
 * <p>Lists bind by index: {@code firstAttempt.1}, {@code firstAttempt.2}, and so on.
 *
 * @param verifyTask   the task name the hub expects
 * @param flagPattern  a regex matching the result, used both to spot it and to validate it
 * @param dataFile     the source to condense, downloaded again on every run
 * @param answerKey    the field the hub wants the condensed text under — the exercise's word
 * @param linePattern  a regex for one source line, with the named groups {@link #GROUPS}
 * @param lineFormat   one submitted line: four {@code %s}, filled with date, time, severity and
 *                     message in that order
 * @param firstAttempt the severities code submits before the model is involved
 * @param budget       the hub's size limit and how much of it is held back
 * @param zoom         the tool that shows raw lines around a moment
 * @param submit       the tool that sends a selection
 * @param answer       the terminal tool: calling it ends the run
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String dataFile,
        String answerKey,
        String linePattern,
        String lineFormat,
        List<String> firstAttempt,
        Budget budget,
        ToolPrompt zoom,
        ToolPrompt submit,
        ToolPrompt answer) {

    /** What {@link #linePattern} has to capture, by name, for a line to become an event. */
    static final Set<String> GROUPS = Set.of("date", "time", "severity", "message");

    /**
     * Each check refuses at the binding boundary, before the transcript is even open, for the reason
     * s02e01's cap does: what they prevent is not a crash where the mistake is but a run that spends
     * and then fails in a way that reads as the model's fault.
     */
    public TaskParams {
        // Each of these binds to null when missing and fails later: the pattern after the download,
        // the answer key and task name at the hub as a malformed submission, the flag pattern after
        // the result was already earned. Blank counts as missing.
        require(verifyTask, "verifyTask");
        require(flagPattern, "flagPattern");
        require(dataFile, "dataFile");
        require(answerKey, "answerKey");
        require(linePattern, "linePattern");
        require(lineFormat, "lineFormat");

        requireGroups(linePattern);
        requireFourSlots(lineFormat);

        if (firstAttempt == null || firstAttempt.isEmpty()) {
            throw new IllegalStateException(
                    "firstAttempt must name at least one severity: it is what code submits before the"
                            + " model is involved, and an empty selection would be sent as nothing and"
                            + " rejected for it. Set firstAttempt.1, firstAttempt.2, ... in the lesson's"
                            + " task.properties.");
        }
        firstAttempt = List.copyOf(firstAttempt);
    }

    /**
     * Compiled here rather than where it is used, so a typo fails before anything is downloaded —
     * and the groups are checked by name, because a pattern that compiles and matches every line can
     * still lack the one group a line is looked up by, which then throws on the first line read.
     */
    private static void requireGroups(String linePattern) {
        Set<String> declared;
        try {
            declared = Pattern.compile(linePattern).namedGroups().keySet();
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(
                    "linePattern is not a valid regex: " + e.getDescription()
                            + ". Remember every backslash is doubled in a properties file.", e);
        }

        Set<String> missing = new TreeSet<>(GROUPS);
        missing.removeAll(declared);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "linePattern must declare the named groups " + new TreeSet<>(GROUPS)
                            + "; it is missing " + missing + ". Add them as (?<name>...).");
        }
    }

    /**
     * Checked by filling it rather than counting {@code %s}. {@code String.formatted} ignores
     * arguments it has nowhere to put, so a format one slot short renders without complaint and
     * drops a field from every submitted line — and a count of {@code %s} would be fooled by
     * {@code %%s} or {@code %1$s}. Four distinct markers either all come out or the format is wrong.
     */
    private static void requireFourSlots(String lineFormat) {
        String[] markers = {"<<date>>", "<<time>>", "<<severity>>", "<<message>>"};
        String rendered;
        try {
            rendered = lineFormat.formatted((Object[]) markers);
        } catch (IllegalFormatException e) {
            throw new IllegalStateException(
                    "lineFormat is not a valid format string: " + e.getMessage(), e);
        }
        for (String marker : markers) {
            if (!rendered.contains(marker)) {
                throw new IllegalStateException(
                        "lineFormat must place all four values — date, time, severity and message — and"
                                + " it drops at least one: " + lineFormat);
            }
        }
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
     * The hub's limit and the part of it held back, validated here so the message names the keys to
     * edit — {@link TokenBudget} refuses the same values, but in words about budgets, not files.
     *
     * @param cap    the hub's size limit, in tokens
     * @param margin tokens held back from the cap, absorbing the difference between our encoding and
     *               the hub's
     */
    public record Budget(int cap, int margin) {

        public Budget {
            if (cap <= 0) {
                throw new IllegalStateException(
                        "budget.cap must be a positive number of tokens, not " + cap
                                + " — a key that was never written binds to 0. Set it in the"
                                + " lesson's task.properties.");
            }
            if (margin < 0 || margin >= cap) {
                throw new IllegalStateException(
                        "budget.margin (" + margin + ") must be at least 0 and smaller than budget.cap ("
                                + cap + "): it is held back from the cap, never added to it.");
            }
        }

        public TokenBudget tokens() {
            return new TokenBudget(cap, margin);
        }
    }

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {}
}
