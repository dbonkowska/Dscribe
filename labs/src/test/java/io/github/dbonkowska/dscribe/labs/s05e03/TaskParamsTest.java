package io.github.dbonkowska.dscribe.labs.s05e03;

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
 * Keys whose mistakes produce a run that cannot succeed, or one that succeeds at the wrong thing. The
 * sharpest is the shift: an {@code int} nobody wrote binds to 0, and a report sent with the found date
 * unshifted reads as a wrong answer about the date rather than as a missing key.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away or spoils it. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            dataDir=/srv/x
            dateShiftDays=-1
            fields.1.name=when
            fields.1.format=date
            fields.2.name=town
            fields.2.format=text
            fields.3.name=x
            fields.3.format=decimal
            shell.name=run
            shell.description=runs one command
            report.name=tell
            report.description=tells the answer
            """;

    private static TaskParams bind(String props) {
        return new JavaPropsMapper().readValue(props, TaskParams.class);
    }

    private static String refusal(String props) {
        return assertThrows(RuntimeException.class, () -> bind(props)).getMessage();
    }

    @Test
    void bindsACompleteFile() {
        TaskParams params = bind(COMPLETE);

        assertEquals("x-task", params.verifyTask());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals("/srv/x", params.dataDir());
        assertEquals(-1, params.dateShiftDays());
        assertEquals(
                List.of(new TaskParams.Field("when", "date"),
                        new TaskParams.Field("town", "text"),
                        new TaskParams.Field("x", "decimal")),
                params.fields());
        assertEquals(new TaskParams.Named("run", "runs one command"), params.shell());
        assertEquals(new TaskParams.Named("tell", "tells the answer"), params.report());
    }

    /** Absent must not mean zero: zero would send the found date as the answer, unshifted. */
    @Test
    void refusesAMissingShiftRatherThanReadingItAsZero() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("dateShiftDays"))
                .collect(Collectors.joining("\n"));

        assertTrue(refusal(props).contains("dateShiftDays"), () -> refusal(props));
    }

    /** The prompt names this path to the model; a relative one would depend on a working directory it cannot see. */
    @Test
    void refusesARelativeDataDir() {
        String props = COMPLETE.replace("dataDir=/srv/x", "dataDir=srv/x");

        assertTrue(refusal(props).contains("dataDir"), () -> refusal(props));
    }

    @Test
    void refusesAFlagPatternThatDoesNotCompile() {
        String props = COMPLETE.replace("flagPattern=[{]F[}]", "flagPattern=[");

        assertTrue(refusal(props).contains("flagPattern"), () -> refusal(props));
    }

    /** The date is the one value code changes; with no field marked, the shift would apply to nothing. */
    @Test
    void refusesFieldsWithNoDate() {
        String props = COMPLETE.replace("fields.1.format=date", "fields.1.format=text");

        assertTrue(refusal(props).contains("date"), () -> refusal(props));
    }

    @Test
    void refusesFieldsWithTwoDates() {
        String props = COMPLETE.replace("fields.2.format=text", "fields.2.format=date");

        String message = refusal(props);
        assertTrue(message.contains("when") && message.contains("town"), message);
    }

    @Test
    void refusesAFormatTheReportCannotWrite() {
        String props = COMPLETE.replace("fields.3.format=decimal", "fields.3.format=money");

        String message = refusal(props);
        assertTrue(message.contains("money") && message.contains("decimal"), message);
    }

    /** The run sends the command under this key, so a field of that name would collide with it. */
    @Test
    void refusesAFieldNamedLikeTheCommandKey() {
        String props = COMPLETE.replace("fields.2.name=town", "fields.2.name=cmd");

        assertTrue(refusal(props).contains(TaskParams.CMD_KEY), () -> refusal(props));
    }

    @Test
    void refusesAFieldNamedTwice() {
        String props = COMPLETE.replace("fields.2.name=town", "fields.2.name=x");

        assertTrue(refusal(props).contains("twice"), () -> refusal(props));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shell.name=run", "shell.description=runs one command", "report.name=tell",
            "report.description=tells the answer", "verifyTask=x-task", "fields.2.name=town"})
    void refusesABlankKey(String line) {
        String key = line.substring(0, line.indexOf('='));
        String props = COMPLETE.replace(line, key + "=  ");

        assertTrue(refusal(props).contains(key.startsWith("fields.") ? "fields.N.name" : key), () -> refusal(props));
    }
}
