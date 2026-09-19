package io.github.dbonkowska.dscribe.labs.s02e05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check standing between what the model wrote and the one call that is judged.
 *
 * <p>What makes this worth its own class is the overloading: one command name carries several
 * meanings, and the argument's format is what picks between them. A wrong format is therefore not
 * a call that fails — it is a different call, made successfully, with nothing in the reply saying
 * a substitution happened. So the check has to identify the intended shape positively, by the form
 * the argument has, rather than accept anything the name allows.
 *
 * <p>The command language here is invented: nothing in this file is supplied by the exercise.
 */
class CommandsTest {

    private static final TaskParams.Dsl DSL = new TaskParams.Dsl(
            List.of(
                    new TaskParams.Shape("alt", "set", "^[0-9]+u$"),
                    new TaskParams.Shape("sec", "set", "^[0-9]+,[0-9]+$"),
                    new TaskParams.Shape("obj", "aim", "^[A-Z]{2}[0-9]+$")),
            List.of("go", "check"),
            "go",
            List.of("alt", "sec"),
            "sec",
            "set(%s,%s)");

    /** The one command the runner owns: assembled from a reading, never written by the model. */
    private static final String RESERVED = "set(3,4)";

    /** Ordered, and carrying the coordinate the runner owns. */
    private static final List<String> VALID =
            List.of("check", "set(5u)", "aim(AB12)", "set(3,4)", "go");

    private static Commands commands() {
        return new Commands(DSL, RESERVED);
    }

    private static String refusalFor(List<String> instructions) {
        return assertThrows(IllegalArgumentException.class, () -> commands().check(instructions))
                .getMessage();
    }

    @Test
    void acceptsASequenceWhoseArgumentsAllMatchAShapeOfTheirCommand() {
        assertDoesNotThrow(() -> commands().check(VALID));
    }

    /** An empty sequence reaches the hub as a well-formed request that asks for nothing. */
    @Test
    void refusesAnEmptySequence() {
        String message = refusalFor(List.of());

        assertTrue(message.toLowerCase().contains("empty"), () -> message);
    }

    /**
     * Anything that is not a bare name or a name with one parenthesised argument. Left to the hub
     * these come back as a rejection of the whole sequence, and the model has to guess which entry
     * was meant.
     */
    @ParameterizedTest
    @ValueSource(strings = {"set(", "set(a", "(x)", "se t(1)", "", "set()", "set(1))"})
    void refusesAnEntryThatIsNotACommand(String entry) {
        String message = refusalFor(List.of(entry));

        assertTrue(message.contains(entry),
                () -> "it has to quote the entry that could not be read: " + message);
    }

    @Test
    void refusesACommandTheLanguageDoesNotHave() {
        String message = refusalFor(List.of("blast(1)"));

        assertTrue(message.contains("blast"),
                () -> "it has to name the command it did not recognise: " + message);
    }

    /**
     * The argument is well-formed and the command exists, and the call is still wrong: no shape of
     * that command matches, so nothing says which of its meanings was intended. The refusal lists
     * what the command does accept, because the model has a reason for what it wrote and needs the
     * supported route rather than only the refusal.
     */
    @Test
    void refusesAnArgumentMatchingNoShapeOfItsCommand() {
        String message = refusalFor(List.of("set(zzz)"));

        assertTrue(message.contains("zzz"), () -> "it has to quote what was written: " + message);
        assertTrue(message.contains("alt") && message.contains("sec"),
                () -> "it has to list the shapes that command does accept: " + message);
    }

    @Test
    void refusesAPlainCommandGivenAnArgument() {
        String message = refusalFor(List.of("go(1)"));

        assertTrue(message.contains("go"), () -> message);
    }

    @Test
    void refusesACommandThatNeedsAnArgumentAndHasNone() {
        String message = refusalFor(List.of("set"));

        assertTrue(message.contains("set"), () -> message);
    }

    /**
     * The one that decides whether the check is real. A sequence is assembled from more than one
     * source — what the model wrote, and what it was handed — so the bad entry is exactly as likely
     * to be second as first, and a loop that stops at index 0 passes a fixture that proves nothing.
     */
    @Test
    void refusesABadEntryThatFollowsAGoodOne() {
        String message = refusalFor(List.of("set(5u)", "set(zzz)", "go"));

        assertTrue(message.contains("zzz"),
                () -> "it has to reach past the first entry: " + message);
    }

    /**
     * The coordinate is the one value nothing downstream can catch: transposed, it still matches
     * its shape, still renders a well-formed command, and still reaches the hub as a sequence that
     * simply acts somewhere else. So the runner assembles it, and anything that differs is refused
     * here rather than quietly replaced — a value overwritten is a disagreement nobody hears.
     */
    @Test
    void refusesAReservedCommandWhoseValueDiffersFromTheAssembledOne() {
        String message = refusalFor(List.of("check", "set(5u)", "set(4,3)", "go"));

        assertTrue(message.contains(RESERVED),
                () -> "the refusal has to name the command to write instead: " + message);
    }

    /** As likely to sit second as first, and a check that stops early never sees it. */
    @Test
    void refusesAReservedCommandThatDiffersInSecondPosition() {
        String message = refusalFor(List.of("set(5u)", "set(9,9)", "go"));

        assertTrue(message.contains(RESERVED), () -> message);
    }

    /**
     * Recognised by the shape its argument has, never as "the entry I did not write". A negative
     * test refuses nothing the moment the model writes something unanticipated, which is exactly
     * when the guard is needed.
     */
    @Test
    void acceptsTheReservedCommandWhenItIsTheAssembledOne() {
        assertDoesNotThrow(() -> commands().check(List.of("set(5u)", RESERVED, "go")));
    }

    /** Without it nothing happens: the sequence configures the machine and never starts it. */
    @Test
    void refusesASequenceWithoutTheTerminalCommand() {
        String message = refusalFor(List.of("check", "set(5u)", "set(3,4)"));

        assertTrue(message.contains("go"),
                () -> "it has to name the command that begins the action: " + message);
    }

    /**
     * The precondition the documentation states: the terminal acts on what was set before it, so a
     * setter after it is a setter the action never saw. Every entry is well-formed, and the hub
     * rejects the sequence for a reason that reads as a wrong value rather than a wrong order.
     */
    @Test
    void refusesAPrerequisiteThatComesAfterTheTerminal() {
        String message = refusalFor(List.of("check", "set(3,4)", "go", "set(5u)"));

        assertTrue(message.contains("alt"),
                () -> "it has to name the prerequisite that arrived too late: " + message);
    }

    /** The degenerate half of the same rule: never set at all, rather than set too late. */
    @Test
    void refusesAPrerequisiteThatNeverAppears() {
        String message = refusalFor(List.of("check", "set(3,4)", "go"));

        assertTrue(message.contains("alt"),
                () -> "it has to name the prerequisite that was never set: " + message);
    }

    /**
     * Without a value, the rule does not switch off — it becomes unsatisfiable. Every entry of the
     * owned shape differs from nothing, so every one is refused, and the model spends the whole
     * iteration budget being told to write something that was never assembled.
     */
    @Test
    void refusesConstructionWithNoAssembledCommand() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> new Commands(DSL, null));

        assertTrue(thrown.getMessage().contains("sec"),
                () -> "it has to name the shape left without a value: " + thrown.getMessage());
    }

    @Test
    void refusesConstructionWithABlankAssembledCommand() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> new Commands(DSL, "   "));

        assertTrue(thrown.getMessage().contains("sec"), thrown::getMessage);
    }

    /**
     * A value that is not the shape it claims makes the run unwinnable before it starts: no entry
     * can both match the owned shape and equal this string, so every sequence is refused however
     * carefully it is written. A broken template or a mis-rendered reading arrives here, and this
     * is the last place it can be caught before the loop is paid for.
     */
    @Test
    void refusesAnAssembledCommandThatIsNotTheShapeItOwns() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> new Commands(DSL, "set(3)"));

        assertTrue(thrown.getMessage().contains("set(3)"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("sec"),
                () -> "it has to name the shape it should have matched: " + thrown.getMessage());
    }

    /** Well-formed, and the wrong shape: this one is the altitude, not the coordinate. */
    @Test
    void refusesAnAssembledCommandMatchingADifferentShape() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> new Commands(DSL, "set(5u)"));

        assertTrue(thrown.getMessage().contains("sec"), thrown::getMessage);
    }
}
