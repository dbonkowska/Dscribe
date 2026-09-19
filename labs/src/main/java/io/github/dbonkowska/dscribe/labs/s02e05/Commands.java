package io.github.dbonkowska.dscribe.labs.s02e05;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The check standing between what the model wrote and the one call that is judged.
 *
 * <p>Pure: no hub, no model, no clock. Everything it decides is decided from its arguments, so
 * every refusal is asserted by equality rather than by watching what did or did not get sent.
 *
 * <p>What makes it worth a class of its own is the overloading. One command name carries several
 * meanings and the argument's format is what picks between them, so a wrong format is not a call
 * that fails — it is a different call, made successfully, with nothing in the reply to say a
 * substitution happened. The check therefore identifies the intended shape positively, by the form
 * the argument has, instead of accepting whatever the name allows.
 */
final class Commands {

    /**
     * A bare name, or a name with one parenthesised argument.
     *
     * <p>The argument may not be empty and may not itself contain a bracket: {@code set()} names no
     * meaning, and {@code set(1))} is a stray bracket rather than an argument of {@code 1)}. Both
     * would otherwise reach the hub as a rejection of the whole sequence, leaving the model to
     * guess which entry was meant.
     */
    private static final Pattern ENTRY = Pattern.compile("([A-Za-z][A-Za-z0-9]*)(?:\\(([^()]+)\\))?");

    /** One accepted form of one command, with its pattern compiled once rather than per check. */
    private record Form(String id, Pattern pattern, String source) {}

    private final Map<String, List<Form>> forms = new LinkedHashMap<>();
    private final List<String> plainCommands;
    private final String terminal;
    private final List<String> prerequisites;
    private final String reservedShape;
    private final String reservedCommand;

    Commands(TaskParams.Dsl dsl, String reservedCommand) {
        for (TaskParams.Shape shape : dsl.shapes()) {
            forms.computeIfAbsent(shape.command(), command -> new ArrayList<>())
                    .add(new Form(shape.id(), Pattern.compile(shape.pattern()), shape.pattern()));
        }
        this.plainCommands = dsl.plainCommands();
        this.terminal = dsl.terminal();
        this.prerequisites = dsl.prerequisites();
        this.reservedShape = dsl.reservedShape();
        this.reservedCommand = reservedCommand;

        // Both refuse here rather than on the first submission. The rule does not switch off when
        // its value is missing or wrong — it becomes unsatisfiable, and an unsatisfiable rule reads
        // from the transcript as a model that will not follow instructions.
        if (reservedCommand == null || reservedCommand.isBlank()) {
            throw new IllegalStateException(
                    "No command was assembled for the " + reservedShape + " shape. Every entry of that"
                            + " shape would then differ from nothing and be refused, and the run would"
                            + " spend its whole budget being told to write a value never built.");
        }
        if (!reservedShape.equals(shapeIdOf(reservedCommand))) {
            throw new IllegalStateException(
                    "The assembled command " + reservedCommand + " is not the " + reservedShape
                            + " shape it is meant to own. No entry can both match that shape and equal"
                            + " this string, so every sequence would be refused however carefully it is"
                            + " written. Check the template and what was rendered through it.");
        }
    }

    /**
     * Refuses before anything is sent, naming the entry and what would have been accepted instead.
     *
     * <p>Every entry is checked, not the first: a sequence is assembled from more than one source —
     * what the model wrote and what it was handed — so a bad entry is as likely to sit second as
     * first.
     */
    void check(List<String> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            throw new IllegalArgumentException(
                    "The instruction sequence is empty. An empty sequence reaches the hub as a"
                            + " well-formed request that asks for nothing to happen.");
        }

        // the shape each entry matched, in step with the sequence — null where the entry was a
        // command taking no argument. The ordering rule is written over shapes rather than names
        // because one name carries several of them.
        List<String> matched = new ArrayList<>();
        for (int i = 0; i < instructions.size(); i++) {
            matched.add(checkEntry(instructions.get(i), i + 1));
        }

        checkOrder(instructions, matched);
    }

    /** @return the id of the shape this entry matched, or null for a command taking no argument */
    private String checkEntry(String entry, int position) {
        Matcher matcher = ENTRY.matcher(entry == null ? "" : entry);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Instruction " + position + " could not be read as a command: \"" + entry + "\"."
                            + " Write either a bare name, or a name with one argument in parentheses.");
        }

        String name = matcher.group(1);
        String argument = matcher.group(2);
        List<Form> accepted = forms.get(name);

        if (accepted == null) {
            if (plainCommands.contains(name)) {
                if (argument != null) {
                    throw new IllegalArgumentException(
                            "Instruction " + position + ": " + name + " takes no argument, and was given"
                                    + " (" + argument + "). Write it on its own.");
                }
                return null;
            }
            throw new IllegalArgumentException(
                    "Instruction " + position + " names " + name + ", which this command language does"
                            + " not have. It has: " + known() + ".");
        }

        if (argument == null) {
            throw new IllegalArgumentException(
                    "Instruction " + position + ": " + name + " takes an argument and was given none."
                            + " Accepted forms of " + name + ": " + describe(accepted) + ".");
        }

        Form form = firstMatching(accepted, argument);
        if (form == null) {
            // the argument is well-formed and the command exists, and the call is still wrong: no
            // form matches, so nothing says which of the command's meanings was intended
            throw new IllegalArgumentException(
                    "Instruction " + position + ": no form of " + name + " accepts \"" + argument + "\"."
                            + " Accepted forms of " + name + ": " + describe(accepted) + ".");
        }

        // Recognised as reserved by the shape it has, never as "the entry I did not write": a
        // negative test refuses nothing the moment the model writes something unanticipated, which
        // is exactly when this guard is needed. Refused rather than replaced, because a value
        // silently overwritten is a disagreement nobody hears.
        if (form.id().equals(reservedShape) && !entry.equals(reservedCommand)) {
            throw new IllegalArgumentException(
                    "Instruction " + position + " writes " + entry + ", but this value is not"
                            + " yours to choose: it was read from the image and assembled here."
                            + " Write " + reservedCommand + " instead.");
        }
        return form.id();
    }

    private static Form firstMatching(List<Form> accepted, String argument) {
        for (Form form : accepted) {
            if (form.pattern().matcher(argument).matches()) {
                return form;
            }
        }
        return null;
    }

    /** @return the id of the shape this entry matches, or null where it matches none */
    private String shapeIdOf(String entry) {
        Matcher matcher = ENTRY.matcher(entry);
        if (!matcher.matches()) {
            return null;
        }
        List<Form> accepted = forms.get(matcher.group(1));
        String argument = matcher.group(2);
        if (accepted == null || argument == null) {
            return null;
        }
        Form form = firstMatching(accepted, argument);
        return form == null ? null : form.id();
    }

    /**
     * The precondition the documentation states: the terminal acts on what was set before it, so a
     * setter placed after it is one the action never saw.
     *
     * <p>Every entry is well-formed either way, and the hub rejects the whole sequence for a reason
     * that reads as a wrong value rather than a wrong order — which sends the model back to
     * re-deriving something it already had right.
     */
    private void checkOrder(List<String> instructions, List<String> matched) {
        int terminalAt = instructions.indexOf(terminal);
        if (terminalAt < 0) {
            throw new IllegalArgumentException(
                    "The sequence never reaches " + terminal + ", so it configures the machine and"
                            + " never starts it. Add " + terminal + " after everything it depends on.");
        }

        for (String id : prerequisites) {
            int at = matched.indexOf(id);
            if (at < 0) {
                throw new IllegalArgumentException(
                        terminal + " requires " + id + " to be set first, and the sequence never sets"
                                + " it. Accepted forms: " + describe(formsFor(id)) + ".");
            }
            if (at > terminalAt) {
                throw new IllegalArgumentException(
                        terminal + " requires " + id + " to be set first, and instruction " + (at + 1)
                                + " sets it after instruction " + (terminalAt + 1) + ". Move it earlier.");
            }
        }
    }

    private List<Form> formsFor(String id) {
        return forms.values().stream()
                .flatMap(List::stream)
                .filter(form -> form.id().equals(id))
                .toList();
    }

    private String known() {
        List<String> names = new ArrayList<>(forms.keySet());
        names.addAll(plainCommands);
        return String.join(", ", names);
    }

    private static String describe(List<Form> accepted) {
        return accepted.stream()
                .map(form -> form.id() + " " + form.source())
                .collect(Collectors.joining(", "));
    }
}
