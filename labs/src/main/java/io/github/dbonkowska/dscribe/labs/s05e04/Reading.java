package io.github.dbonkowska.dscribe.labs.s05e04;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a garbled status reply says, positively, or that it cannot be told.
 *
 * <p>Regex over the raw body rather than a lenient JSON parse, because the garbling is exactly what
 * a parser refuses: mismatched quotes and missing commas. Re-reading does not help, since the
 * garbling is stable within a column, so {@link Unreadable} is an answer and not a reason to wait.
 */
sealed interface Reading {

    /** The whole reply was the clear marker. Nothing else opens the gate. */
    record Clear() implements Reading {}

    /** A refused step, with what clearing it takes. The code is the raw value, case kept. */
    record Refused(int number, String code) implements Reading {

        /** The algorithm was refused at binding if it cannot be computed, so a throw here is a bug. */
        String digest(String algorithm, String suffix) {
            try {
                byte[] hash = MessageDigest.getInstance(algorithm)
                        .digest((code + suffix).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    record Unreadable() implements Reading {}

    /**
     * Reads replies under the names and swaps a lesson binds.
     *
     * <p>Case and swaps are undone on keys and the clear text, never on values: the digest is taken
     * over the value as it stands, and a fold there would send a wrong hash that looks right.
     *
     * @param clearMarker  the phrase a clear reply is, compared on letters and digits with repeats
     *                     collapsed. The whole body must match, so a phrase quoted inside some other
     *                     reply cannot pass
     * @param numberKey the key the number sits under
     * @param codeKey      the key the code sits under
     * @param confusables  character pairs a reply swaps, each folded to its first character
     */
    record Decoder(String clearMarker, String numberKey, String codeKey, List<String> confusables) {

        private static final String QUOTE = "[\"'`]";

        /** Quotes may mismatch within a token. A value that opens a block matches nothing and is skipped. */
        private static final Pattern TOKEN = Pattern.compile(
                QUOTE + "([^\"'`\\s:]+)" + QUOTE + "\\s*:\\s*"
                        + "(?:" + QUOTE + "([^\"'`\\n]*)" + QUOTE + "|([^\\s,\"'`{}\\[\\]]+))");

        /** Nine digits always fit an {@code int}, so a longer run reads as not a number. */
        private static final Pattern NUMBER = Pattern.compile("\\d{1,9}");

        Reading read(String body) {
            if (bare(body).equals(bare(clearMarker))) {
                return new Clear();
            }
            Map<String, String> tokens = tokens(body);
            String number = tokens.get(fold(numberKey));
            String code = tokens.get(fold(codeKey));
            if (number == null || !NUMBER.matcher(number).matches() || code == null || code.isBlank()) {
                return new Unreadable();
            }
            return new Refused(Integer.parseInt(number), code);
        }

        private Map<String, String> tokens(String body) {
            Map<String, String> tokens = new HashMap<>();
            Matcher token = TOKEN.matcher(body);
            while (token.find()) {
                String value = token.group(2) != null ? token.group(2) : token.group(3);
                tokens.putIfAbsent(fold(token.group(1)), value);
            }
            return tokens;
        }

        private String bare(String text) {
            return fold(text).replaceAll("[^\\p{L}\\p{N}]", "").replaceAll("(.)\\1+", "$1");
        }

        private String fold(String text) {
            String folded = text.toLowerCase(Locale.ROOT);
            for (String pair : confusables) {
                String lower = pair.toLowerCase(Locale.ROOT);
                folded = folded.replace(lower.charAt(1), lower.charAt(0));
            }
            return folded;
        }
    }
}
