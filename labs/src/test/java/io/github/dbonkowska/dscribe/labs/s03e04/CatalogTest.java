package io.github.dbonkowska.dscribe.labs.s03e04;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Names and codes here are invented. The exercise's own catalog is task content and does not
 * belong in this repository — see {@code Rows}' test for the same rule applied to s02e01.
 */
class CatalogTest {

    private static final String CITIES = """
            name,code
            City One,C1
            City Two,C2
            """;

    private static final String ITEMS = """
            name,code
            Widget A,I1
            Widget B,I2
            """;

    private static final String CONNECTIONS = """
            itemCode,cityCode
            I1,C1
            I1,C2
            I2,C2
            """;

    @Test
    void looksUpTheCitiesConnectedToAnItemCode() {
        Catalog catalog = Catalog.of(CITIES, ITEMS, CONNECTIONS);

        assertEquals(List.of("City One", "City Two"), catalog.citiesFor("I1"));
        assertEquals(List.of("City Two"), catalog.citiesFor("I2"));
    }

    @Test
    void refusesAConnectionNamingAnItemCodeNotInTheItemsFile() {
        String connections = "itemCode,cityCode\nI3,C1\n";

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> Catalog.of(CITIES, ITEMS, connections));

        assertTrue(thrown.getMessage().contains("I3"), thrown::getMessage);
    }

    /** "Known" is what the items file declares, so an item nothing sells is still known. */
    @Test
    void knowsEveryCodeTheItemsFileDeclaresAndNoOther() {
        String items = ITEMS + "Widget C,I3\n";
        Catalog catalog = Catalog.of(CITIES, items, CONNECTIONS);

        assertTrue(catalog.knows("I1"));
        assertTrue(catalog.knows("I3"), "declared but sold nowhere is still a real item");
        assertEquals(List.of(), catalog.citiesFor("I3"));
        assertFalse(catalog.knows("BOGUS"));
    }

    /** The same connection twice would list a city twice, and nothing downstream says so. */
    @Test
    void refusesADuplicatedConnection() {
        String connections = CONNECTIONS + "I1,C1\n";

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> Catalog.of(CITIES, ITEMS, connections));

        assertTrue(thrown.getMessage().contains("I1"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("C1"), thrown::getMessage);
    }

    /** A code declared twice would silently keep whichever name came last. */
    @Test
    void refusesACodeDeclaredTwiceInOneFile() {
        String items = ITEMS + "Widget A again,I1\n";

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> Catalog.of(CITIES, items, CONNECTIONS));

        assertTrue(thrown.getMessage().contains("I1"), thrown::getMessage);
    }

    @Test
    void refusesAConnectionNamingACityCodeNotInTheCitiesFile() {
        String connections = "itemCode,cityCode\nI1,C9\n";

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> Catalog.of(CITIES, ITEMS, connections));

        assertTrue(thrown.getMessage().contains("C9"), thrown::getMessage);
    }
}
