package io.github.dbonkowska.dscribe.labs.s03e04;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The whole per-request behaviour: resolve the free-text query to item codes, join each to its
 * cities, union the results, and bound the reply.
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

    private ItemLookup() {}

    static String answer(
            String query, Catalog catalog, Resolver resolver, int minBytes, int maxBytes) {

        List<String> itemCodes = resolver.resolve(query);

        // a LinkedHashSet: stable order (first-seen city wins its position) and no duplicate when
        // two resolved codes happen to share a city, which two codes from one ambiguous query
        // often will
        Set<String> cities = new LinkedHashSet<>();
        for (String itemCode : itemCodes) {
            cities.addAll(catalog.citiesFor(itemCode));
        }

        return ToolReply.of(List.copyOf(cities), minBytes, maxBytes);
    }
}
