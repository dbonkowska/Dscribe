package io.github.dbonkowska.dscribe.labs.s05e02;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only thing the model decides in a call, narrowed so a wrong label or an invented choice
 * cannot be represented — and checked again in code, because a provider can ignore the schema.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class ClassificationTest {

    private static final TaskParams TASK = new TaskParams(
            "x-task", "[{]F[}]", "open", "v1",
            List.of("x", "y"),
            List.of(new TaskParams.Stage("s1"), new TaskParams.Stage("s2 {chosen}")),
            List.of(new TaskParams.Interrupt("a", "answer a")));

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    @Test
    void narrowsTheLabelToTheDialoguesOwnPlusEveryInterrupt() {
        JsonNode schema = Classification.schema(TASK);

        assertEquals(List.of(TaskParams.PROCEED, TaskParams.OTHER, "a"), strings(schema.at("/properties/label/enum")));
    }

    @Test
    void narrowsWhatCanBeChosenToTheCandidates() {
        JsonNode schema = Classification.schema(TASK);

        assertEquals("array", schema.at("/properties/chosen/type").asString());
        assertEquals(List.of("x", "y"), strings(schema.at("/properties/chosen/items/enum")));
    }

    @Test
    void requiresBothKeysAndNothingElse() {
        JsonNode schema = Classification.schema(TASK);

        assertEquals(List.of("label", "chosen"), strings(schema.path("required")));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }

    @Test
    void readsALabelAndWhatItChose() {
        Classification read = Classification.parse(Map.of("label", "a", "chosen", List.of("y")), TASK);

        assertEquals(new Classification("a", List.of("y")), read);
    }

    @Test
    void readsAMissingChoiceAsNothingChosen() {
        assertEquals(List.of(), Classification.parse(Map.of("label", "a"), TASK).chosen());
    }

    @Test
    void refusesALabelOutsideTheSet() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Classification.parse(Map.of("label", "z", "chosen", List.of()), TASK));

        assertTrue(thrown.getMessage().contains("z"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("[proceed, other, a]"), thrown::getMessage);
    }

    @Test
    void refusesAChoiceThatIsNotACandidate() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Classification.parse(Map.of("label", "proceed", "chosen", List.of("q")), TASK));

        assertTrue(thrown.getMessage().contains("q"), thrown::getMessage);
    }
}
