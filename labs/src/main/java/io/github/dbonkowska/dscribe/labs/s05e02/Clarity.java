package io.github.dbonkowska.dscribe.labs.s05e02;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Whether a synthesised line comes back from a listener with its codes intact.
 *
 * <p>Speech synthesis is not deterministic, and the same sentence that passed in one run can run a
 * conjunction into a code in the next — "i CD56" heard as "ECD 56", a code nobody knows. The
 * other side then refuses the whole line, for a reason that names the content rather than the
 * audio. So every clip is transcribed back before it is sent, and a code the line names but the
 * transcription does not is a clip to synthesise again.
 *
 * <p>A code counts as heard as a whole word, with spaces, hyphens and case allowed between its
 * characters ("ab 12", "AB-12"). A letter or digit directly beside it makes it a different code,
 * which is exactly the miss this exists for: "ECD56" contains "CD56" and is not it.
 */
final class Clarity {

    private Clarity() {}

    /** The candidates {@code said} names that {@code heard} does not, in the candidates' order. */
    static List<String> missing(String said, String heard, List<String> candidates) {
        return candidates.stream()
                .filter(candidate -> spoken(candidate).matcher(said).find())
                .filter(candidate -> !spoken(candidate).matcher(heard).find())
                .toList();
    }

    private static Pattern spoken(String candidate) {
        String chars = candidate.chars()
                .mapToObj(c -> Pattern.quote(String.valueOf((char) c)))
                .collect(Collectors.joining("[\\s-]*"));
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + chars + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
