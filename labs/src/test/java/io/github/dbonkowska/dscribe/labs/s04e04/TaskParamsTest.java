package io.github.dbonkowska.dscribe.labs.s04e04;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose mistakes produce a run that cannot succeed. A string key nobody wrote binds to null
 * rather than failing, and would fail later and further from the file: a missing file name after
 * the archive was fetched, a missing action name inside the batch, a missing flag pattern after
 * the result was already earned.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            archive.url=https://hub.test/data/bundle.zip
            archive.file=bundle.zip
            notes.needs=wants.txt
            notes.calls=diary.txt
            notes.transactions=ledger.txt
            transactions.separator=->
            dirs.cities=places
            dirs.people=folk
            dirs.goods=things
            actions.help=manual
            actions.reset=wipe
            actions.createDirectory=mkdir
            actions.createFile=write
            actions.done=finish
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = bind(COMPLETE);

        assertEquals("x-task", params.verifyTask());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals("https://hub.test/data/bundle.zip", params.archive().url());
        assertEquals("bundle.zip", params.archive().file());
        assertEquals("wants.txt", params.notes().needs());
        assertEquals("diary.txt", params.notes().calls());
        assertEquals("ledger.txt", params.notes().transactions());
        assertEquals("->", params.transactions().separator());
        assertEquals("places", params.dirs().cities());
        assertEquals("folk", params.dirs().people());
        assertEquals("things", params.dirs().goods());
        assertEquals("manual", params.actions().help());
        assertEquals("wipe", params.actions().reset());
        assertEquals("mkdir", params.actions().createDirectory());
        assertEquals("write", params.actions().createFile());
        assertEquals("finish", params.actions().done());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "archive.url", "archive.file", "notes.needs", "notes.calls",
            "notes.transactions", "transactions.separator", "dirs.cities", "dirs.people", "dirs.goods",
            "actions.help", "actions.reset", "actions.createDirectory", "actions.createFile", "actions.done"})
    void refusesARequiredKeyThatWasNeverWritten(String key) {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith(key + "="))
                .collect(Collectors.joining("\n"));

        // Jackson wraps anything a record constructor throws, so the type on this path is its
        // wrapper rather than ours. The binding fails and the message names the key to edit.
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains(key),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    /** Present but empty is the same mistake as absent, and has to be refused the same way. */
    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "archive.url", "archive.file", "notes.needs", "notes.calls",
            "notes.transactions", "transactions.separator", "dirs.cities", "dirs.people", "dirs.goods",
            "actions.help", "actions.reset", "actions.createDirectory", "actions.createFile", "actions.done"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /**
     * A pattern that does not compile throws on the first reply it is applied to, after the run has
     * already spent and the hub has printed the result it was meant to find.
     */
    @Test
    void refusesAFlagPatternThatDoesNotCompile() {
        String props = COMPLETE.replace("flagPattern=[{]F[}]", "flagPattern=[a-z");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("flagPattern"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("backslash"),
                () -> "it has to say how properties files mangle patterns: " + thrown.getMessage());
    }

    /**
     * The hub refuses a name used twice anywhere in the tree, and two kinds of file sharing one
     * directory would overwrite each other where their names meet.
     */
    @Test
    void refusesTwoDirectoriesUnderOneName() {
        String props = COMPLETE.replace("dirs.goods=things", "dirs.goods=folk");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(props));

        assertTrue(thrown.getMessage().contains("dirs.people"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("dirs.goods"), thrown::getMessage);
    }

    /** Stored stripped: a padded name would be sent with its padding and refused by the hub. */
    @Test
    void storesValuesStripped() {
        String props = COMPLETE.replace("dirs.cities=places", "dirs.cities=places  ");

        assertEquals("places", bind(props).dirs().cities());
    }

    private static TaskParams bind(String props) {
        return new JavaPropsMapper().readValue(props, TaskParams.class);
    }
}
