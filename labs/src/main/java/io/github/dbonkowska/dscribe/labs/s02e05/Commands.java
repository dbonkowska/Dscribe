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

    Commands(TaskParams.Dsl dsl) {
        for (TaskParams.Shape shape : dsl.shapes()) {
            forms.computeIfAbsent(shape.command(), command -> new ArrayList<>())
                    .add(new Form(shape.id(), Pattern.compile(shape.pattern()), shape.pattern()));
        }
        this.plainCommands = dsl.plainCommands();
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

        for (int i = 0; i < instructions.size(); i++) {
            checkEntry(instructions.get(i), i + 1);
        }
    }

    private void checkEntry(String entry, int position) {
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
                return;
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

        for (Form form : accepted) {
            if (form.pattern().matcher(argument).matches()) {
                return;
            }
        }

        // the argument is well-formed and the command exists, and the call is still wrong: no form
        // matches, so nothing says which of the command's meanings was intended
        throw new IllegalArgumentException(
                "Instruction " + position + ": no form of " + name + " accepts \"" + argument + "\"."
                        + " Accepted forms of " + name + ": " + describe(accepted) + ".");
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
