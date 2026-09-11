package io.github.dbonkowska.dscribe.labs.s02e01;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Measures text the way the judge is expected to, so an over-long prompt is caught here rather
 * than bought from an authority that charges to answer.
 *
 * <p>{@code o200k_base} is an approximation and is meant to be. The encoding the current model
 * family actually uses is {@code o200k_harmony}, which this library does not ship — it shares the
 * same base merge table and adds special tokens for the chat envelope, which a bare prompt string
 * never carries. The gap that remains is absorbed by the caller's margin, and the judge stays the
 * authority on its own arithmetic.
 *
 * <p>The registry is built once. It loads and parses a vocabulary of two hundred thousand entries,
 * which is cheap once and absurd per call in a loop that measures every rendered prompt.
 */
final class Tokens {

    private static final Encoding ENCODING =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    private Tokens() {}

    static int count(String text) {
        return ENCODING.countTokens(text);
    }
}
