package io.github.dbonkowska.dscribe.labs.s03e04;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The whole per-request behaviour: resolve the free-text query to item codes, drop the ones the
 * catalog has never heard of, join the rest to their cities, union the results, and bound the
 * reply.
 *
 * <p>{@code Resolver} is a seam of its own rather than {@code ChatTransport}, for the reason
 * {@code s03e01.Judge} and {@code s02e05.GridReader} both have one: {@code LlmClient.sendStructured}
 * is not declared on the library's transport interface, so a scripted fake is the only way to test
 * this class's own logic — the union, the ordering, the empty case — without a real model call.
 */
final class ItemLookup {

    @FunctionalInterface
    interface Resolver {
        /** @return the item codes the query most plausibly describes, or none at all */
        List<String> resolve(String query);
    }

    /**
     * What one request came to: the reply to send, and the codes that were refused on the way.
     *
     * <p>The refused codes travel beside the reply rather than inside it. They are not for the
     * caller, who would take a list of invented codes as something to act on, but they are the
     * only trace that the model named something that does not exist.
     */
    record Answer(String reply, List<String> unknownCodes) {}

    private ItemLookup() {}

    /**
     * A closed vocabulary too large for a schema {@code enum} — a few thousand codes — is held in
     * code instead. The schema still allows any string, so this is where a wrong one is refused.
     */
    static Answer answer(
            String query, Catalog catalog, Resolver resolver, int minBytes, int maxBytes) {

        List<String> unknown = new ArrayList<>();

        // a LinkedHashSet: stable order (first-seen city wins its position) and no duplicate when
        // two resolved codes happen to share a city, which two codes from one ambiguous query
        // often will
        Set<String> cities = new LinkedHashSet<>();
        for (String itemCode : resolver.resolve(query)) {
            if (catalog.knows(itemCode)) {
                cities.addAll(catalog.citiesFor(itemCode));
            } else {
                unknown.add(itemCode);
            }
        }

        return new Answer(ToolReply.of(List.copyOf(cities), minBytes, maxBytes), List.copyOf(unknown));
    }
}
