package io.github.dbonkowska.dscribe.labs.s04e04;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The one note with a fixed format, read by code rather than by the model: every line is a
 * seller, a good and a buyer. Its places are written whole, in the form a file is named by, so
 * they become the closed set every place the model names is narrowed to.
 *
 * @param cities every seller and buyer, sorted
 * @param sales  each distinct seller and good, in the order first seen; goods as written
 */
record TradeLog(List<String> cities, List<Sale> sales) {

    record Sale(String seller, String good) {}

    static TradeLog parse(List<String> lines, String separator) {
        Pattern split = Pattern.compile(Pattern.quote(separator));
        Set<String> cities = new TreeSet<>();
        Set<Sale> sales = new LinkedHashSet<>();

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = split.split(line, -1);
            List<String> stripped = new ArrayList<>();
            for (String part : parts) {
                stripped.add(part.strip());
            }
            if (stripped.size() != 3 || stripped.stream().anyMatch(String::isEmpty)) {
                throw new IllegalStateException(
                        "A trade log line splits on \"" + separator + "\" into " + stripped + " rather than"
                                + " a seller, a good and a buyer: \"" + line + "\"");
            }
            cities.add(stripped.get(0));
            cities.add(stripped.get(2));
            sales.add(new Sale(stripped.get(0), stripped.get(1)));
        }
        if (sales.isEmpty()) {
            throw new IllegalStateException("The trade log has no lines, so there is nothing to narrow a city to.");
        }
        return new TradeLog(List.copyOf(cities), List.copyOf(sales));
    }

    /** The goods as written, distinct, in the order first seen. */
    List<String> goods() {
        return sales.stream().map(Sale::good).distinct().toList();
    }
}
