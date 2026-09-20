package io.github.dbonkowska.dscribe.labs.s03e01;

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
 * <p>This lesson leans on the file harder than any before it. The channel definitions are the
 * whole of the deterministic pass — a channel nobody wrote is a channel never range-checked, and
 * the run then submits a set missing every record that channel would have caught. Nothing
 * downstream disagrees with it, because the hub's rejection names nothing. The judge's knobs
 * decide how much is spent and whether the cheaper deduplication unit is ever chosen, and both
 * bind to zero when absent, which is the value that makes each of them useless in a different
 * direction.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise. Patterns avoid
 * backslashes on purpose — every one of them would have to be doubled in a properties file, and
 * a fixture that needs explaining is a fixture that will be copied wrongly.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below breaks exactly one of them. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            answerKey=findings
            archive.url=https://hub.test/data/bundle.zip
            archive.file=bundle.zip
            archive.idPattern=^([0-9]+)[.]json$
            fields.type=kind
            fields.notes=remark
            fields.typeSeparator=/
            fields.clauseSeparator=,
            channels.1.name=alpha
            channels.1.field=alpha_units
            channels.1.min=10.0
            channels.1.max=20.0
            channels.2.name=beta
            channels.2.field=beta_units
            channels.2.min=100.0
            channels.2.max=200.0
            stance.vocabulary.1=clear
            stance.vocabulary.2=concern
            stance.vocabulary.3=silent
            stance.problem=concern
            judge.batchSize=50
            judge.sampleSize=25
            judge.agreementThreshold=0.9
            """;

    private static TaskParams bind(String props) {
        return new JavaPropsMapper().readValue(props, TaskParams.class);
    }

    private static String without(String key) {
        return COMPLETE.lines()
                .filter(line -> !line.startsWith(key + "="))
                .collect(Collectors.joining("\n"));
    }

    private static String withoutAllUnder(String prefix) {
        return COMPLETE.lines()
                .filter(line -> !line.startsWith(prefix))
                .collect(Collectors.joining("\n"));
    }

    private static String blank(String key) {
        return with(key, "   ");
    }

    private static String with(String key, String value) {
        return COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=" + value : line)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void bindsACompleteFile() {
        TaskParams params = bind(COMPLETE);

        assertEquals("x-task", params.verifyTask());
        assertEquals("findings", params.answerKey());
        assertEquals("bundle.zip", params.archive().file());
        assertEquals("kind", params.fields().type());
        assertEquals("/", params.fields().typeSeparator());
        assertEquals(
                List.of(
                        new TaskParams.Channel("alpha", "alpha_units", 10.0, 20.0),
                        new TaskParams.Channel("beta", "beta_units", 100.0, 200.0)),
                params.channels(),
                "channels bind by index, and their order is the order refusals list them in");
        assertEquals(List.of("clear", "concern", "silent"), params.stance().vocabulary());
        assertEquals("concern", params.stance().problem());
        assertEquals(50, params.judge().batchSize());
        assertEquals(25, params.judge().sampleSize());
        assertEquals(0.9, params.judge().agreementThreshold());
    }

    /**
     * A missing string key binds to null without complaint, and each of these fails somewhere
     * that does not name it. Without the field names the corpus reads as empty records and every
     * one of them looks clean; without the answer key the hub is sent a well-formed envelope with
     * nothing in the slot it reads.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "answerKey",
            "archive.url", "archive.file", "archive.idPattern",
            "fields.type", "fields.notes", "fields.typeSeparator", "fields.clauseSeparator",
            "stance.problem"})
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
            "verifyTask", "flagPattern", "answerKey",
            "archive.url", "archive.file", "archive.idPattern",
            "fields.type", "fields.notes", "fields.typeSeparator", "fields.clauseSeparator",
            "stance.problem"})
    void refusesARequiredKeyLeftBlank(String key) {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> bind(blank(key)));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /**
     * A whole block absent, rather than one key of it. These bind to null and are refused by a
     * different message from the per-key one — the message that lists what to write — so it is a
     * different path and needs its own cases.
     */
    @ParameterizedTest
    @ValueSource(strings = {"archive.", "fields.", "stance.", "judge."})
    void refusesAWholeBlockThatWasNeverWritten(String prefix) {
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> bind(withoutAllUnder(prefix)));

        String key = prefix.substring(0, prefix.length() - 1);
        assertTrue(thrown.getMessage().contains(key),
                () -> "it has to name the block to write: " + thrown.getMessage());
    }

    /**
     * The channel list is the entire deterministic pass. With none of it, nothing is ever
     * range-checked, every record reads as clean, and the run submits whatever the notes alone
     * produced — a plausible answer, quietly missing a whole category.
     */
    @Test
    void refusesAFileWithNoChannels() {
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> bind(withoutAllUnder("channels.")));

        assertTrue(thrown.getMessage().contains("channels"), thrown::getMessage);
    }

    /**
     * Two channels under one name: the second silently shadows the first, so one configured
     * channel is never checked and the loss looks like nothing at all.
     */
    @Test
    void refusesADuplicateChannelName() {
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> bind(with("channels.2.name", "alpha")));

        assertTrue(thrown.getMessage().contains("alpha"),
                () -> "it has to name the channel written twice: " + thrown.getMessage());
    }

    /**
     * A bound nobody wrote binds to zero, and a channel whose range is {@code [0, 0]} rejects
     * every reading it sees. Requiring the maximum strictly above the minimum catches that and
     * the transposed pair in one check.
     */
    @Test
    void refusesAChannelWhoseMaximumIsNotAboveItsMinimum() {
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> bind(with("channels.2.max", "50.0")));

        assertTrue(thrown.getMessage().contains("beta"),
                () -> "it has to name the channel whose bounds are wrong: " + thrown.getMessage());
    }

    /** A problem stance no answer can carry means the run flags nothing, whatever it is told. */
    @Test
    void refusesAProblemStanceOutsideTheVocabulary() {
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> bind(with("stance.problem", "alarm")));

        assertTrue(thrown.getMessage().contains("alarm"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("concern"),
                () -> "it has to list what the vocabulary does allow: " + thrown.getMessage());
    }

    /** Zero sends an empty batch, gets an empty answer, and does it again forever. */
    @Test
    void refusesABatchSizeBelowOne() {
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> bind(with("judge.batchSize", "0")));

        assertTrue(thrown.getMessage().contains("judge.batchSize"), thrown::getMessage);
    }

    /** Zero makes the composition check pass vacuously, so the cheaper unit is always chosen. */
    @Test
    void refusesASampleSizeBelowOne() {
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> bind(with("judge.sampleSize", "0")));

        assertTrue(thrown.getMessage().contains("judge.sampleSize"), thrown::getMessage);
    }

    /** Zero accepts any disagreement; above one can never be met, so nothing is ever accepted. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "1.5"})
    void refusesAnAgreementThresholdOutsideItsRange(String value) {
        RuntimeException thrown = assertThrows(
                RuntimeException.class, () -> bind(with("judge.agreementThreshold", value)));

        assertTrue(thrown.getMessage().contains("judge.agreementThreshold"), thrown::getMessage);
    }

    /** A pattern that does not compile throws after the run has already earned its result. */
    @Test
    void refusesAFlagPatternThatDoesNotCompile() {
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> bind(with("flagPattern", "[0-9")));

        assertTrue(thrown.getMessage().contains("flagPattern"),
                () -> "it has to name the key whose regex is broken: " + thrown.getMessage());
    }

    /**
     * The id pattern is read for its first group, not for whether it matched. A pattern with no
     * group compiles, matches, and then throws on the first file of ten thousand.
     */
    @Test
    void refusesAnIdPatternWithNoCapturingGroup() {
        RuntimeException thrown = assertThrows(
                RuntimeException.class, () -> bind(with("archive.idPattern", "^[0-9]+[.]json$")));

        assertTrue(thrown.getMessage().contains("archive.idPattern"), thrown::getMessage);
    }

    /** Unused in the sweeps above because it is not a string; absent, it binds to an empty list. */
    @Test
    void refusesAnEmptyStanceVocabulary() {
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> bind(withoutAllUnder("stance.vocabulary")));

        assertTrue(thrown.getMessage().contains("stance.vocabulary"), thrown::getMessage);
    }
}
