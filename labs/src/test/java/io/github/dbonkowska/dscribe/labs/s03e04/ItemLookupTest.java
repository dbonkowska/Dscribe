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

    private static final String BOTH_CITIES = ToolReply.of(List.of("City One", "City Two"), 4, 500);

    @Test
    void resolvesOneItemToTheCitiesConnectedToIt() {
        ItemLookup.Resolver resolver = query -> List.of("I1");

        ItemLookup.Answer answer = ItemLookup.answer("a widget", CATALOG, resolver, 4, 500);

        assertEquals(BOTH_CITIES, answer.reply());
        assertEquals(List.of(), answer.unknownCodes());
    }

    /**
     * I1 and I2 both connect to City Two — the union must not repeat it just because two
     * resolved codes happened to share a city.
     */
    @Test
    void unionsCitiesAcrossSeveralResolvedCodesWithoutDuplicating() {
        ItemLookup.Resolver resolver = query -> List.of("I1", "I2");

        ItemLookup.Answer answer = ItemLookup.answer("a widget or another", CATALOG, resolver, 4, 500);

        assertEquals(BOTH_CITIES, answer.reply());
    }

    @Test
    void returnsTheNoMatchReplyWhenNothingResolves() {
        ItemLookup.Resolver resolver = query -> List.of();

        ItemLookup.Answer answer = ItemLookup.answer("nothing like this exists", CATALOG, resolver, 4, 500);

        assertEquals(ToolReply.NO_MATCH, answer.reply());
    }

    /**
     * A code the model invented is dropped, and reported rather than lost. Left in, it joins to no
     * cities exactly as a real item nobody sells would, and the caller is told to rephrase a
     * query whose wording was fine. The bogus code goes second so the check has to look at every
     * element, not only the first.
     */
    @Test
    void dropsACodeTheCatalogDoesNotKnowAndReportsIt() {
        ItemLookup.Resolver resolver = query -> List.of("I1", "BOGUS");

        ItemLookup.Answer answer = ItemLookup.answer("a widget", CATALOG, resolver, 4, 500);

        assertEquals(BOTH_CITIES, answer.reply());
        assertEquals(List.of("BOGUS"), answer.unknownCodes());
    }

    /** Nothing the model named exists: the reply is the no-match one, and the codes are kept. */
    @Test
    void answersNoMatchButStillReportsEveryCodeWhenNoneWasReal() {
        ItemLookup.Resolver resolver = query -> List.of("BOGUS", "ALSO-BOGUS");

        ItemLookup.Answer answer = ItemLookup.answer("a widget", CATALOG, resolver, 4, 500);

        assertEquals(ToolReply.NO_MATCH, answer.reply());
        assertEquals(List.of("BOGUS", "ALSO-BOGUS"), answer.unknownCodes());
    }
}
