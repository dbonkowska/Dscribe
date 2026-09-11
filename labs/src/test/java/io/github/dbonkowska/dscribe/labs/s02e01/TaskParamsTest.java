package io.github.dbonkowska.dscribe.labs.s02e01;

import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cap} is a primitive, and a primitive binds a missing key to zero rather than failing —
 * the trap the principles already name under "blank configuration means absent, not configured".
 *
 * <p>Zero is the worst possible value here, because of where it surfaces. The effective cap
 * becomes {@code 0 - margin}, every candidate is refused for being longer than a negative number,
 * and the refusal reaches the model as a tool result. It shortens, is refused, shortens again —
 * the same unsatisfiable loop a wrong column name once produced, one config key over, and with no
 * message an operator could act on. So it is refused at the binding boundary instead, before the
 * transcript is even open.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    @Test
    void refusesACapThatWasNeverSetInTheFile() {
        String props = """
                prompt.idPlaceholder={id}
                prompt.descriptionPlaceholder={description}
                prompt.margin=5
                """;

        // Jackson wraps anything a record constructor throws, so the type on this path is its
        // wrapper rather than ours. What matters to whoever reads the crash is unchanged: the
        // binding fails, and the message names the key to edit.
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("prompt.cap"),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    @Test
    void refusesAMarginThatWouldLeaveNothingSendable() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new TaskParams.Prompt("{a}", "{b}", 40, 40));

        assertTrue(thrown.getMessage().contains("prompt.margin"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("prompt.cap"), thrown::getMessage);
    }

    @Test
    void refusesANegativeMargin() {
        assertThrows(IllegalStateException.class, () -> new TaskParams.Prompt("{a}", "{b}", 40, -1));
    }

    @Test
    void acceptsAMarginSmallerThanTheCap() {
        assertEquals(5, new TaskParams.Prompt("{a}", "{b}", 40, 5).margin());
    }

    /** Zero is a real choice — measure exactly, trust the encoding — and must stay expressible. */
    @Test
    void acceptsNoMarginAtAll() {
        assertEquals(0, new TaskParams.Prompt("{a}", "{b}", 40, 0).margin());
    }
}
