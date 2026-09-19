package io.github.dbonkowska.dscribe.labs.s02e05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose mistakes produce a run that spends before it fails, or one that cannot succeed.
 *
 * <p>This lesson hands the file three things no earlier one did. The command shapes decide which
 * overload a written argument selects, so a wrong one is not refused but dispatched somewhere
 * else. The grid dimensions and anchor are the only thing standing between a miscounted reading
 * and a well-formed sequence that lands on the wrong place — an anchor outside its own grid can
 * never be matched, so every reading is rejected and the run dies having paid for all of them.
 * And the reserved template renders the one command the runner owns; a template that lost a slot
 * renders a command naming one number, which no shape matches.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below breaks exactly one of them. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            docUrl=https://docs.test/api.html
            image.file=map.png
            image.mediaType=image/png
            grid.columns=9
            grid.rows=6
            grid.anchorColumn=2
            grid.anchorRow=3
            grid.readAttempts=3
            dsl.shapes.1.id=alt
            dsl.shapes.1.command=set
            dsl.shapes.1.pattern=^[0-9]+u$
            dsl.shapes.2.id=sec
            dsl.shapes.2.command=set
            dsl.shapes.2.pattern=^[0-9]+,[0-9]+$
            dsl.shapes.3.id=obj
            dsl.shapes.3.command=aim
            dsl.shapes.3.pattern=^[A-Z]{2}[0-9]+$
            dsl.plainCommands.1=go
            dsl.plainCommands.2=check
            dsl.terminal=go
            dsl.prerequisites.1=alt
            dsl.prerequisites.2=sec
            dsl.reservedShape=sec
            dsl.reservedTemplate=set(%s,%s)
            submit.name=send
            submit.description=sends a sequence
            """;

    private static TaskParams bind(String props) {
        return new JavaPropsMapper().readValue(props, TaskParams.class);
    }

    private static String without(String key) {
        return COMPLETE.lines()
                .filter(line -> !line.startsWith(key + "="))
                .collect(Collectors.joining("\n"));
    }

    private static String blank(String key) {
        return COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void bindsACompleteFile() {
        TaskParams params = bind(COMPLETE);

        assertEquals(
                List.of(
                        new TaskParams.Shape("alt", "set", "^[0-9]+u$"),
                        new TaskParams.Shape("sec", "set", "^[0-9]+,[0-9]+$"),
                        new TaskParams.Shape("obj", "aim", "^[A-Z]{2}[0-9]+$")),
                params.dsl().shapes(),
                "shapes bind by index, and their order is the order refusals list them in");
        assertEquals(List.of("go", "check"), params.dsl().plainCommands());
        assertEquals("go", params.dsl().terminal());
        assertEquals(List.of("alt", "sec"), params.dsl().prerequisites());
        assertEquals("sec", params.dsl().reservedShape());
        assertEquals(9, params.grid().columns());
        assertEquals(6, params.grid().rows());
        assertEquals(3, params.grid().readAttempts());
        assertEquals("map.png", params.image().file());
        assertEquals("send", params.submit().name());
    }

    /**
     * A missing string key binds to null without complaint. Without the task name every submission
     * reaches the hub malformed and reads as a wrong sequence; without the documentation URL the
     * model is never told the command language at all; without the flag pattern the run dies after
     * the result has already been earned.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "docUrl", "image.file", "image.mediaType",
            "dsl.terminal", "dsl.reservedShape", "dsl.reservedTemplate", "submit.name", "submit.description"})
    void refusesARequiredKeyThatWasNeverWritten(String key) {
        // Jackson wraps anything a record constructor throws, so the type on this path is its
        // wrapper rather than ours. What matters is unchanged: binding fails, naming the key to edit.
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(without(key)));

        assertTrue(thrown.getMessage().contains(key),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    /** Present but empty is the same mistake as absent, and has to be refused the same way. */
    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "docUrl", "image.file", "image.mediaType",
            "dsl.terminal", "dsl.reservedShape", "dsl.reservedTemplate", "submit.name", "submit.description"})
    void refusesARequiredKeyLeftBlank(String key) {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(blank(key)));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /**
     * Two shapes under one id: a prerequisite or the reserved shape naming it means one of the two,
     * and nothing says which. The ordering check would enforce whichever came first.
     */
    @Test
    void refusesADuplicateShapeId() {
        String props = COMPLETE.replace("dsl.shapes.3.id=obj", "dsl.shapes.3.id=alt");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("alt"),
                () -> "it has to name the id written twice: " + thrown.getMessage());
    }

    /**
     * The terminal is the command the ordering rule is written around. One that takes an argument,
     * or that nothing may emit, makes that rule unsatisfiable — every sequence is refused for a
     * precondition no sequence can meet.
     */
    @Test
    void refusesATerminalThatIsNotAPlainCommand() {
        String props = COMPLETE.replace("dsl.terminal=go", "dsl.terminal=fly");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("fly"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("plainCommands"), thrown::getMessage);
    }

    /** A prerequisite naming no shape can never be matched, so the terminal is never reachable. */
    @Test
    void refusesAPrerequisiteNamingNoShape() {
        String props = COMPLETE.replace("dsl.prerequisites.1=alt", "dsl.prerequisites.1=nope");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("nope"),
                () -> "it has to name the prerequisite with no shape: " + thrown.getMessage());
    }

    /**
     * The reserved shape is how the runner recognises the command it owns. Naming no shape means
     * nothing is ever recognised as reserved, and a transposed value from the model passes straight
     * through the one check written to catch it.
     */
    @Test
    void refusesAReservedShapeNamingNoShape() {
        String props = COMPLETE.replace("dsl.reservedShape=sec", "dsl.reservedShape=nope");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("nope"), thrown::getMessage);
    }

    /**
     * Checked by rendering it rather than by looking for {@code %s}: {@code String.formatted}
     * discards an argument it has nowhere to put, so a template that lost a slot renders cleanly
     * and silently drops half the coordinate — and a search for {@code %s} would be fooled by
     * {@code %%s}, which counts and fills nothing.
     */
    @Test
    void refusesAReservedTemplateWithOneSlot() {
        String props = COMPLETE.replace("dsl.reservedTemplate=set(%s,%s)", "dsl.reservedTemplate=set(%s)");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("reservedTemplate"), thrown::getMessage);
    }

    /** A third placeholder has no argument, and would throw only once the runner rendered it. */
    @Test
    void refusesAReservedTemplateThatCannotBeRendered() {
        String props = COMPLETE.replace(
                "dsl.reservedTemplate=set(%s,%s)", "dsl.reservedTemplate=set(%s,%s,%s)");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("reservedTemplate"), thrown::getMessage);
    }

    /**
     * A dimension nobody wrote binds to zero, and a grid with no columns is one the reading can
     * never agree with — every attempt is rejected and the run dies having paid for all of them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"grid.columns", "grid.rows"})
    void refusesAGridDimensionThatIsNotPositive(String key) {
        String props = blank(key).replace(key + "=   ", key + "=0");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /**
     * An anchor outside its own grid cannot be matched by any reading, so the check meant to
     * calibrate the model rejects every attempt instead — and the transcript reads as a model that
     * cannot count.
     */
    @Test
    void refusesAnAnchorOutsideTheGrid() {
        String props = COMPLETE.replace("grid.anchorColumn=2", "grid.anchorColumn=10");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("anchorColumn"), thrown::getMessage);
    }

    /** Zero attempts reads every reading as exhausted, so the run fails without ever looking. */
    @Test
    void refusesReadAttemptsBelowOne() {
        String props = COMPLETE.replace("grid.readAttempts=3", "grid.readAttempts=0");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("readAttempts"), thrown::getMessage);
    }

    /** A pattern that does not compile throws on the first sequence, after the loop is paid for. */
    @Test
    void refusesAShapePatternThatDoesNotCompile() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new TaskParams.Shape("alt", "set", "[0-9"));

        assertTrue(thrown.getMessage().contains("alt"),
                () -> "it has to name the shape whose pattern is broken: " + thrown.getMessage());
    }

    /** A blank pattern matches only the empty argument, so every real one would be refused. */
    @Test
    void refusesAShapeWithABlankPattern() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new TaskParams.Shape("alt", "set", "   "));

        assertTrue(thrown.getMessage().contains("alt"), thrown::getMessage);
    }
}
