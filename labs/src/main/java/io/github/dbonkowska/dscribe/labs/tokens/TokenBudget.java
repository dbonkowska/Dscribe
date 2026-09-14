package io.github.dbonkowska.dscribe.labs.tokens;

/**
 * How large something may be before an authority that charges to answer will refuse it, and
 * whether a given measurement is under that.
 *
 * <p>Only the arithmetic is shared. Nothing here throws on size: a refusal is read by a model, and
 * what it should say — "shorten the prompt", "remove entries" — belongs to the caller that knows
 * what the model was trying to send.
 *
 * @param cap    the size the authority enforces
 * @param margin tokens held back from the cap. It *tightens* the cap: we measure with an
 *               approximation of the authority's encoding, so the error worth guarding against is
 *               measuring a hair under what it will measure — the direction where something looks
 *               sendable and is not
 */
public record TokenBudget(int cap, int margin) {

    /**
     * Each of these produces a guard that refuses everything, and the refusal reaches the model as
     * a tool result it answers by shortening — forever, with nothing naming a value anyone could
     * change. Refused here instead, where the mistake is.
     */
    public TokenBudget {
        if (cap <= 0) {
            throw new IllegalArgumentException(
                    "A token cap must be positive, not " + cap + " — a key that was never written binds to 0.");
        }
        if (margin < 0) {
            throw new IllegalArgumentException(
                    "A margin cannot be negative, and " + margin + " is. It is held back from the cap, never added.");
        }
        if (margin >= cap) {
            throw new IllegalArgumentException(
                    "A margin of " + margin + " leaves nothing under a cap of " + cap + " that could be sent.");
        }
    }

    /**
     * The limit a measurement is actually held to.
     *
     * <p>Exposed because a model has to be told it, and the number it needs is this one rather than
     * the raw cap — read from here, so what it is told and what is enforced cannot drift apart.
     */
    public int effectiveCap() {
        return cap - margin;
    }

    public int measure(String text) {
        return Tokens.count(text);
    }

    /** Inclusive: something measuring exactly the effective cap is sendable. */
    public boolean fits(int measured) {
        return measured <= effectiveCap();
    }
}
