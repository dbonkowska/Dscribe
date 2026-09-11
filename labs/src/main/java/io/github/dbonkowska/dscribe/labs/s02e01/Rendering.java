package io.github.dbonkowska.dscribe.labs.s02e01;

/**
 * Turns a candidate template into the text actually sent, and refuses one that will not fit.
 *
 * <p>Configured once from the lesson bundle and reused for every cycle: the placeholders, the cap
 * and the margin are facts about the exercise, where the template changes on each attempt. Nothing
 * here knows what the template says — only that it has somewhere to put a row.
 *
 * <p>Both refusals throw. {@code Toolbox} turns anything a tool handler throws into a tool result
 * the model reads, so a candidate that cannot be sent costs one model iteration and nothing from
 * the budget — which is the whole reason to measure locally rather than let the judge answer.
 */
final class Rendering {

    private final String idPlaceholder;
    private final String descriptionPlaceholder;

    /**
     * The largest a rendered prompt may measure here, already reduced by the margin.
     *
     * <p>The margin *tightens* the cap. We measure with an approximation of the judge's encoding,
     * so the error worth guarding against is measuring a hair under what it will measure — the
     * direction where a prompt looks sendable and is not.
     */
    private final int effectiveCap;

    Rendering(String idPlaceholder, String descriptionPlaceholder, int cap, int margin) {
        this.idPlaceholder = idPlaceholder;
        this.descriptionPlaceholder = descriptionPlaceholder;
        this.effectiveCap = cap - margin;
    }

    /**
     * The limit a rendered prompt is actually held to.
     *
     * <p>Exposed because the model has to be told it. The first run spent its opening round
     * discovering the limit by being refused, which is a round bought for nothing — and the
     * number it needs is this one, not the lesson's raw cap, so it is read from here rather than
     * recomputed anywhere the two could drift apart.
     */
    int effectiveCap() {
        return effectiveCap;
    }

    /**
     * Checked before a cycle spends anything — a template with nowhere to put a row would
     * otherwise be sent once per row, identical every time, and rejected on its own merits far
     * downstream of the actual mistake.
     */
    void requirePlaceholders(String template) {
        require(template, idPlaceholder);
        require(template, descriptionPlaceholder);
    }

    private void require(String template, String placeholder) {
        if (!template.contains(placeholder)) {
            throw new IllegalArgumentException(
                    "The prompt must contain the placeholder " + placeholder
                            + ", which is replaced with the row's value before sending. Add it.");
        }
    }

    /**
     * Every occurrence is replaced, not the first: a template may well want to name the row twice.
     *
     * <p>Validates again rather than trusting the caller. The two are separate entry points
     * because the cycle wants to fail before it downloads anything, not because one is the
     * unchecked version of the other.
     */
    String render(String template, Item item) {
        requirePlaceholders(template);

        String rendered = template
                .replace(idPlaceholder, item.id())
                .replace(descriptionPlaceholder, item.description());

        int measured = Tokens.count(rendered);
        if (measured > effectiveCap) {
            throw new IllegalArgumentException(
                    "That prompt measures " + measured + " tokens once a row is filled in, and "
                            + effectiveCap + " is the most that can be sent. Make it shorter.");
        }
        return rendered;
    }
}
