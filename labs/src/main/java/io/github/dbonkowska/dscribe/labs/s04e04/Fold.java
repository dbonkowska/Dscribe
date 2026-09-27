package io.github.dbonkowska.dscribe.labs.s04e04;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One spelling for everything the run writes to the hub. The same fold makes a file's name and
 * the link that points at it, so a link cannot miss its target by a letter.
 */
final class Fold {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Fold() {}

    /**
     * ASCII, case kept. {@code ł} is mapped before normalising: it is a letter of its own, not
     * {@code l} plus a combining mark, so NFD leaves it whole and stripping marks would pass it
     * through untouched.
     */
    static String text(String value) {
        String plain = value.replace('ł', 'l').replace('Ł', 'L');
        return MARKS.matcher(Normalizer.normalize(plain, Normalizer.Form.NFD)).replaceAll("");
    }

    /** A file name: folded, lowercase, and one underscore for each run of whitespace. */
    static String name(String value) {
        return WHITESPACE.matcher(text(value).strip().toLowerCase(Locale.ROOT)).replaceAll("_");
    }
}
