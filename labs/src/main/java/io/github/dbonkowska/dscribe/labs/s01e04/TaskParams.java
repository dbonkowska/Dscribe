package io.github.dbonkowska.dscribe.labs.s01e04;

import java.util.List;

/**
 * Bound from {@code {labs.lessons.dir}/s01e04/task.properties}.
 *
 * <p>Everything the model reads lives outside the repository: the briefing it is handed, and the
 * names and descriptions of the two tools it is offered. Only the component names are Java, being
 * structure rather than content — and here they are load-bearing structure, because
 * {@link Briefing}'s names are the placeholders {@code user.md} is filled through.
 *
 * @param verifyTask the task name {@code hub.verify} expects
 * @param fetch      the tool the run reads the documentation with
 * @param answer     the terminal tool: calling it ends the run and its arguments are the answer
 */
public record TaskParams(
        String verifyTask,
        Briefing briefing,
        Form form,
        ToolPrompt fetch,
        ToolPrompt answer) {

    /**
     * The parts of the document the form fixes, rather than the run deriving them.
     *
     * <p>Both earned their place by failing first. A run was rejected for answering
     * {@code "A - Strategiczna"} where the form takes a bare letter, and another for writing a
     * paragraph of justification into a field the briefing said to leave alone — the second
     * despite the prompt saying so in as many words. Asking is weaker than not offering the
     * field: {@code categories} narrows the answer schema so a wrong one is unrepresentable, and
     * {@code remarks} is filled by the runner, so there is nothing for the model to fill in.
     *
     * @param categories the vocabulary the template itself lists, as an indexed list
     * @param remarks    what the remarks slot says, the briefing having asked for none
     */
    public record Form(List<String> categories, String remarks) {}

    /**
     * What the run is handed, as opposed to what it has to work out for itself. Substituted into
     * {@code user.md} by component name, so renaming one here means renaming a placeholder there
     * — and leaving a stale name behind fails loudly rather than silently, because an unfilled
     * placeholder survives into the message.
     *
     * <p>{@code mass} and {@code budget} are Strings rather than numbers: they are quoted into a
     * message and, for the five that reappear in the declaration, compared against what the model
     * echoed back. Parsing them would only invite formatting drift between the two.
     */
    public record Briefing(
            String sender,
            String origin,
            String destination,
            String mass,
            String contents,
            String budget,
            String docsUrl) {}

    /** The prompt-facing half of a tool — the only half the exercise supplies. */
    public record ToolPrompt(String name, String description) {}
}
