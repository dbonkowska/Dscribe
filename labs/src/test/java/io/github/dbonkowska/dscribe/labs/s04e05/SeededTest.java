package io.github.dbonkowska.dscribe.labs.s04e05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot the delete guard protects. A reply read wrongly does not fail on its own — it comes
 * back as an empty set, and an empty set protects nothing while looking exactly like a start with no
 * seeded orders. So the one empty case that is honest is an empty list, and every other shape is
 * refused.
 *
 * <p>Field names and ids are invented.
 */
class SeededTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsEveryIdInTheList() {
        Set<String> ids = Seeded.ids(MAPPER.readTree("{\"items\":[{\"id\":\"a1\",\"n\":1},{\"id\":\"b2\"}]}"), "items");

        assertEquals(Set.of("a1", "b2"), ids);
    }

    @Test
    void readsAnEmptyListAsNoSeededOrders() {
        assertEquals(Set.of(), Seeded.ids(MAPPER.readTree("{\"items\":[]}"), "items"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"other\":[]}",
            "{\"items\":{\"id\":\"a1\"}}",
            "{\"items\":[{\"id\":\"a1\"},{\"name\":\"b2\"}]}",
            "{\"items\":[{\"id\":\"a1\"},{\"id\":7}]}",
            "{\"items\":[{\"id\":\"a1\"},\"b2\"]}"})
    void refusesAReplyItCannotReadWhole(String reply) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Seeded.ids(MAPPER.readTree(reply), "items"));

        assertTrue(thrown.getMessage().contains("items"),
                () -> "it has to name the field it was reading: " + thrown.getMessage());
    }
}
