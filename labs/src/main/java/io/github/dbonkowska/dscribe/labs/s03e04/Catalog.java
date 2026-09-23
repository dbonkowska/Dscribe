package io.github.dbonkowska.dscribe.labs.s03e04;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The join across the exercise's three files: a name and a code for two entity kinds, and a table
 * connecting them. Built once at startup — a request never re-parses the source files, and a
 * connection naming a code neither file declares fails here rather than as a silent empty result
 * three requests later. So does a code declared twice, or a connection listed twice: either would
 * be resolved without a word, by keeping whichever came last or listing a city twice.
 */
final class Catalog {

    private final Set<String> itemCodes;
    private final Map<String, List<String>> citiesByItemCode;

    private Catalog(Set<String> itemCodes, Map<String, List<String>> citiesByItemCode) {
        this.itemCodes = itemCodes;
        this.citiesByItemCode = citiesByItemCode;
    }

    static Catalog of(String citiesCsv, String itemsCsv, String connectionsCsv) {
        Map<String, String> cityNameByCode = codeToName(citiesCsv, "cities file");
        Map<String, String> itemNameByCode = codeToName(itemsCsv, "items file");

        Map<String, List<String>> citiesByItemCode = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        try (var parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(connectionsCsv))) {

            for (CSVRecord record : parser) {
                String itemCode = record.get("itemCode");
                String cityCode = record.get("cityCode");

                require(itemNameByCode, itemCode, "an item code", "items file");
                require(cityNameByCode, cityCode, "a city code", "cities file");

                if (!seen.add(itemCode + "\u0000" + cityCode)) {
                    throw new IllegalStateException(
                            "The connections file lists item " + itemCode + " with city " + cityCode
                                    + " more than once. Either the download is corrupt or the file"
                                    + " was concatenated twice.");
                }

                citiesByItemCode
                        .computeIfAbsent(itemCode, key -> new ArrayList<>())
                        .add(cityNameByCode.get(cityCode));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the connections file", e);
        }

        return new Catalog(Set.copyOf(itemNameByCode.keySet()), citiesByItemCode);
    }

    /**
     * Whether the items file declares this code. Not whether anything sells it: an item with no
     * connections is a real item, and telling the two apart is what lets a code a model invented
     * be told from one that merely has no cities.
     */
    boolean knows(String itemCode) {
        return itemCodes.contains(itemCode);
    }

    /** The cities connected to an item code, or an empty list if the code sells nothing. */
    List<String> citiesFor(String itemCode) {
        return citiesByItemCode.getOrDefault(itemCode, List.of());
    }

    private static Map<String, String> codeToName(String csv, String file) {
        Map<String, String> byCode = new LinkedHashMap<>();
        try (var parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csv))) {

            for (CSVRecord record : parser) {
                String code = record.get("code");
                if (byCode.putIfAbsent(code, record.get("name")) != null) {
                    throw new IllegalStateException(
                            "The " + file + " declares the code " + code + " more than once. One"
                                    + " name would silently replace the other.");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the " + file, e);
        }
        return byCode;
    }

    private static void require(Map<String, String> names, String code, String label, String file) {
        if (!names.containsKey(code)) {
            throw new IllegalStateException(
                    "A connection names " + label + " (" + code + ") that is not in the " + file
                            + ". Either the download is incomplete or the files disagree.");
        }
    }
}
