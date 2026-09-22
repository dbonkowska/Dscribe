package io.github.dbonkowska.dscribe.labs.s03e04;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ties a scripted {@code Resolver}, {@code Catalog} and {@code ToolReply} together — the whole
 * per-request behaviour, minus the real model call, which is exercised only by a run.
 */
class ItemLookupTest {

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

    private static final Catalog CATALOG = Catalog.of(CITIES, ITEMS, CONNECTIONS);

    @Test
    void resolvesOneItemToTheCitiesConnectedToIt() {
        ItemLookup.Resolver resolver = query -> List.of("I1");

        String reply = ItemLookup.answer("a widget", CATALOG, resolver, 4, 500);

        assertEquals(ToolReply.of(List.of("City One", "City Two"), 4, 500), reply);
    }

    /**
     * I1 and I2 both connect to City Two — the union must not repeat it just because two
     * resolved codes happened to share a city.
     */
    @Test
    void unionsCitiesAcrossSeveralResolvedCodesWithoutDuplicating() {
        ItemLookup.Resolver resolver = query -> List.of("I1", "I2");

        String reply = ItemLookup.answer("a widget or another", CATALOG, resolver, 4, 500);

        assertEquals(ToolReply.of(List.of("City One", "City Two"), 4, 500), reply);
    }

    @Test
    void returnsTheNoMatchReplyWhenNothingResolves() {
        ItemLookup.Resolver resolver = query -> List.of();

        String reply = ItemLookup.answer("nothing like this exists", CATALOG, resolver, 4, 500);

        assertEquals(ToolReply.NO_MATCH, reply);
    }
}
