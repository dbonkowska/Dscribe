package io.github.dbonkowska.dscribe.labs.s05e05;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The year/level table from the saved docs, read once at startup.
 *
 * <p>The table is the contiguous run of lines starting with {@code |} around its first separator row.
 * Rows above the separator are the header. Every row below it is (year, level) pairs repeated across
 * the row, all of them numbers. Anything else below the separator is refused rather than skipped, since
 * a skipped row reads the same as a year the docs never listed.
 */
final class LevelTable {

    /** One cell, padded; a number with any leading zeros. One class each side, possessive, no alternation. */
    private static final Pattern NUMBER = Pattern.compile("\\s*+(\\d++)\\s*+");

    /** One cell of the row between header and body, alignment colons allowed. */
    private static final Pattern SEPARATOR = Pattern.compile("\\s*+:?+-++:?+\\s*+");

    private final Map<Integer, Integer> levels;

    private LevelTable(Map<Integer, Integer> levels) {
        this.levels = Map.copyOf(levels);
    }

    static LevelTable parse(String doc) {
        List<String> lines = doc.lines().map(String::strip).toList();
        int separator = -1;
        for (int i = 0; i < lines.size() && separator < 0; i++) {
            if (isRow(lines.get(i)) && cells(lines.get(i)).stream().allMatch(c -> SEPARATOR.matcher(c).matches())) {
                separator = i;
            }
        }
        if (separator < 0) {
            throw new IllegalStateException(
                    "The docs hold no table: no row of | --- | cells was found, so no year has a level.");
        }
        Map<Integer, Integer> levels = new HashMap<>();
        for (int i = separator + 1; i < lines.size() && isRow(lines.get(i)); i++) {
            read(lines.get(i), levels);
        }
        if (levels.isEmpty()) {
            throw new IllegalStateException("The docs' table has a header but no rows, so no year has a level.");
        }
        return new LevelTable(levels);
    }

    /** The level for {@code year}, refused by name rather than defaulted when the docs never list it. */
    int require(int year) {
        Integer level = levels.get(year);
        if (level == null) {
            throw new IllegalStateException(
                    "The docs' table has no level for " + year + ", so the setting for a jump there is"
                            + " unknown.");
        }
        return level;
    }

    private static void read(String row, Map<Integer, Integer> levels) {
        List<String> cells = cells(row);
        if (cells.size() % 2 != 0) {
            throw new IllegalStateException(
                    "The docs' table row " + row + " has " + cells.size() + " cells. Rows are (year, level)"
                            + " pairs, so one year would have no level and every pair after it would shift.");
        }
        for (int i = 0; i < cells.size(); i += 2) {
            int year = number(cells.get(i), row);
            int level = number(cells.get(i + 1), row);
            if (levels.putIfAbsent(year, level) != null) {
                throw new IllegalStateException(
                        "The docs' table lists " + year + " twice, so its level is ambiguous.");
            }
        }
    }

    private static int number(String cell, String row) {
        Matcher matcher = NUMBER.matcher(cell);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "The docs' table row " + row + " has the cell '" + cell.strip() + "', which is not a"
                            + " number. Below the separator every cell is a year or a level.");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static boolean isRow(String line) {
        return line.startsWith("|");
    }

    /** The cells between the outer bars, so {@code | a | b |} is two cells and not four. */
    private static List<String> cells(String row) {
        String inner = row.substring(1);
        if (inner.endsWith("|")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        return new ArrayList<>(List.of(inner.split("\\|", -1)));
    }
}
