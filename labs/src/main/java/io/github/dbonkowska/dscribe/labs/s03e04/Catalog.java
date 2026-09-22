package io.github.dbonkowska.dscribe.labs.s03e04;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The join across the exercise's three files: a name and a code for two entity kinds, and a table
 * connecting them. Built once at startup — a request never re-parses the source files, and a
 * connection naming a code neither file declares fails here rather than as a silent empty result
 * three requests later.
 */
final class Catalog {

    private final Map<String, List<String>> citiesByItemCode;

    private Catalog(Map<String, List<String>> citiesByItemCode) {
        this.citiesByItemCode = citiesByItemCode;
    }

    static Catalog of(String citiesCsv, String itemsCsv, String connectionsCsv) {
        Map<String, String> cityNameByCode = codeToName(citiesCsv);
        Map<String, String> itemNameByCode = codeToName(itemsCsv);

        Map<String, List<String>> citiesByItemCode = new LinkedHashMap<>();
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

                citiesByItemCode
                        .computeIfAbsent(itemCode, key -> new ArrayList<>())
                        .add(cityNameByCode.get(cityCode));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the connections file", e);
        }

        return new Catalog(citiesByItemCode);
    }

    /** The cities connected to an item code, or an empty list if the code sells nothing. */
    List<String> citiesFor(String itemCode) {
        return citiesByItemCode.getOrDefault(itemCode, List.of());
    }

    private static Map<String, String> codeToName(String csv) {
        Map<String, String> byCode = new LinkedHashMap<>();
        try (var parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csv))) {

            for (CSVRecord record : parser) {
                byCode.put(record.get("code"), record.get("name"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read a catalog file", e);
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
