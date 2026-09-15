package io.github.dbonkowska.dscribe.labs.tokens;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Measures text the way an authority counting in OpenAI's tokenizer is expected to, so an
 * over-long submission is caught here rather than bought from one that charges to answer.
 *
 * <p>Shared rather than per lesson because the second lesson that needed it arrived: the count is
 * a fact about whoever enforces the limit, not about the run's own model, and that authority is
 * the same hub each time. Which is also why it lives in {@code labs} and not in {@code llm-core} —
 * nothing in the library counts, and a seam with no caller there would be the application leaking
 * into it.
 *
 * <p>{@code o200k_base} is an approximation and is meant to be. The encoding the current model
 * family actually uses is {@code o200k_harmony}, which this library does not ship — it shares the
 * same base merge table and adds special tokens for the chat envelope, which a bare string never
 * carries. The gap that remains is absorbed by the caller's margin, and the authority stays the
 * authority on its own arithmetic.
 *
 * <p>The registry is built once. It loads and parses a vocabulary of two hundred thousand entries,
 * which is cheap once and absurd per call in a loop that measures every candidate.
 */
public final class Tokens {

    private static final Encoding ENCODING =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    private Tokens() {}

    public static int count(String text) {
        return ENCODING.countTokens(text);
    }
}
