package io.github.dbonkowska.dscribe.labs.s05e02;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s05e02/task.properties}.
 *
 * <p>The code knows that a session is opened, that turns are said in order, that the other party
 * may interrupt at any point with a question that has a fixed answer, and that one turn names what
 * an earlier reply chose. What is said, what can be chosen and what the interruptions are called
 * are the exercise's, so they are here.
 *
 * @param verifyTask  the task name the hub expects
 * @param flagPattern a regex matching a reply that carries the result
 * @param startAction the action that opens a session
 * @param voice       the voice the turns are spoken in. A runner choice rather than the
 *                    exercise's, but it is only valid for one voice model, so it lives beside the
 *                    turns it speaks rather than in Java
 * @param candidates  what a reply can choose from, as an indexed list: {@code candidates.1=…}. The
 *                    classifier's {@code chosen} is narrowed to these
 * @param stages      the turns, in order: {@code stages.1.say=…}. At most one may contain
 *                    {@value #CHOSEN_SLOT}, filled with what has been chosen by the time it is said
 * @param interrupts  questions the other party may ask at any stage, each answered without leaving
 *                    it: {@code interrupts.1.name}, {@code interrupts.1.say}
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        String startAction,
        String voice,
        List<String> candidates,
        List<Stage> stages,
        List<Interrupt> interrupts) {

    /** The label for a reply that answered the current stage. The dialogue's own, not the exercise's. */
    static final String PROCEED = "proceed";

    /** The label for a reply nothing else fits. The dialogue's own, not the exercise's. */
    static final String OTHER = "other";

    static final String CHOSEN_SLOT = "{chosen}";

    /** Each check refuses at the binding boundary, before the transcript is even open. */
    public TaskParams {
        verifyTask = require(verifyTask, "verifyTask");
        flagPattern = require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");
        startAction = require(startAction, "startAction");
        voice = require(voice, "voice");

        candidates = nonEmpty(candidates, "candidates", "candidates.1=…");
        Set<String> seen = new HashSet<>();
        for (String candidate : candidates) {
            if (!seen.add(require(candidate, "candidates.N"))) {
                throw new IllegalStateException(
                        "candidates lists " + candidate + " twice. Each candidate must appear once.");
            }
        }
        candidates = candidates.stream().map(String::strip).toList();

        stages = nonEmpty(stages, "stages", "stages.1.say=…");
        int slotted = 0;
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).say().contains(CHOSEN_SLOT)) {
                // nothing has been heard before the first turn, so it has nothing to name
                if (i == 0) {
                    throw new IllegalStateException(
                            "stages.1 contains " + CHOSEN_SLOT + ", but nothing can have been chosen before the"
                                    + " first turn. Move it to a later stage.");
                }
                slotted++;
            }
        }
        if (slotted > 1) {
            throw new IllegalStateException(
                    CHOSEN_SLOT + " appears in " + slotted + " stages. At most one turn names what was chosen.");
        }

        interrupts = nonEmpty(interrupts, "interrupts", "interrupts.1.name=… and interrupts.1.say=…");
        Set<String> names = new HashSet<>();
        for (Interrupt interrupt : interrupts) {
            // refused by name, not by whether it collides: these two are the dialogue's labels,
            // and an interrupt sharing one would make a reply mean two things at once
            if (PROCEED.equals(interrupt.name()) || OTHER.equals(interrupt.name())) {
                throw new IllegalStateException(
                        "interrupts names " + interrupt.name() + ", which is reserved: " + PROCEED + " and "
                                + OTHER + " are the dialogue's own labels. Give the interrupt another name.");
            }
            if (!names.add(interrupt.name())) {
                throw new IllegalStateException(
                        "interrupts names " + interrupt.name() + " twice. Each interrupt must appear once.");
            }
        }
    }

    /** One turn, said in order. */
    public record Stage(String say) {
        public Stage {
            say = require(say, "stages.N.say");
        }
    }

    /** A question the other party may ask at any stage, and the fixed answer to it. */
    public record Interrupt(String name, String say) {
        public Interrupt {
            name = require(name, "interrupts.N.name");
            say = require(say, "interrupts.N.say");
        }
    }

    private static <T> List<T> nonEmpty(List<T> list, String key, String example) {
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException(
                    key + " is missing: set " + example + " in the lesson's task.properties.");
        }
        return List.copyOf(list);
    }

    /**
     * Compiled here rather than where it is applied, so a typo fails before a session opens: a
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
