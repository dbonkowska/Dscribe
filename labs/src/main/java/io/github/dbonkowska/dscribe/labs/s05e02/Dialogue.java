package io.github.dbonkowska.dscribe.labs.s05e02;

import java.util.List;

/**
 * Where a call is and what to say next, decided by code rather than by the model. Turn order is
 * what the other side grades and a wrong turn ends the session, so the model only labels each
 * reply; this turns the label into the next line.
 *
 * <p>Immutable, and free of audio and of the hub, so every decision it makes can be asserted
 * without a session. Interrupts — questions the other side may ask at any point — are answered in
 * place: the stage they interrupted is still the one waiting for an answer.
 *
 * @param stage  the stage last said, counted from 1 as in {@code task.properties}
 * @param chosen what replies have chosen so far. A later non-empty choice replaces an earlier one;
 *               an empty one changes nothing, because a reply that names nothing has not shown
 *               that the earlier choice was wrong
 */
public record Dialogue(TaskParams task, int stage, List<String> chosen) {

    public Dialogue {
        chosen = List.copyOf(chosen);
    }

    /** The call's first line, before anything has been heard. */
    public static Step opening(TaskParams task) {
        return new Step(new Dialogue(task, 1, List.of()), new Say(task.stages().getFirst().say(), true));
    }

    /**
     * What to say after a reply.
     *
     * @param label       what the reply was: {@link TaskParams#PROCEED}, {@link TaskParams#OTHER} or an
     *                    interrupt's name — already checked against that set by the classification
     * @param heardChosen what this reply chose, possibly nothing
     * @param heard       the reply as transcribed, quoted when the session burns on it
     */
    public Step step(String label, List<String> heardChosen, String heard) {
        Dialogue merged = new Dialogue(task, stage, heardChosen.isEmpty() ? chosen : heardChosen);

        if (TaskParams.OTHER.equals(label)) {
            return new Step(merged, new Burnt("stage " + stage + ": a reply nothing fits: \"" + heard + "\""));
        }
        if (TaskParams.PROCEED.equals(label)) {
            return merged.advance();
        }
        for (TaskParams.Interrupt interrupt : task.interrupts()) {
            if (interrupt.name().equals(label)) {
                return new Step(merged, new Say(interrupt.say(), true));
            }
        }
        throw new IllegalArgumentException("Unknown label " + label + ": classification should have refused it.");
    }

    private Step advance() {
        if (stage == task.stages().size()) {
            return new Step(this, new Burnt("stages ran out: all " + stage + " said and no flag came back"));
        }
        Dialogue next = new Dialogue(task, stage + 1, chosen);
        String say = task.stages().get(stage).say();
        if (!say.contains(TaskParams.CHOSEN_SLOT)) {
            return new Step(next, new Say(say, true));
        }
        if (chosen.isEmpty()) {
            return new Step(next, new Burnt("nothing chosen before stage " + next.stage + ", which has to name it"));
        }
        // in the candidates' order rather than the reply's, so the same choice always reads the same
        String named = String.join(", ", task.candidates().stream().filter(chosen::contains).toList());
        return new Step(next, new Say(say.replace(TaskParams.CHOSEN_SLOT, named), false));
    }

    /** The dialogue after a reply, and what to do about it. */
    public record Step(Dialogue dialogue, Next next) {}

    public sealed interface Next permits Say, Burnt {}

    /**
     * A line to say.
     *
     * @param prerecorded whether it is one of the fixed lines synthesised before the session opened.
     *                    A line that names what was chosen cannot be, so it is synthesised when said
     */
    public record Say(String text, boolean prerecorded) implements Next {}

    /** The session cannot go on, and why. */
    public record Burnt(String reason) implements Next {}
}
