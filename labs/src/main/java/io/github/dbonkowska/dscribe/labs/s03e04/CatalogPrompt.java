package io.github.dbonkowska.dscribe.labs.s03e04;

import java.util.IllegalFormatException;

/**
 * Fills the catalog into the resolver's system prompt, refusing a template it would not reach.
 *
 * <p>Checked by what the template renders rather than by counting its placeholders. An escaped
 * {@code %%s} counts as one and fills nothing, and a check that parses the syntax has to
 * re-implement the formatter to be right. The formatter decides instead: render once with a marker
 * in the slot and require the marker in the output.
 *
 * <p>The marker is never a real value. It costs nothing and needs no data, so the check runs at
 * startup, before the catalog is read into anything and before the first call is paid for.
 */
final class CatalogPrompt {

    /** Plain text no prompt would contain by accident, and no character nobody can see. */
    static final String MARKER = "CATALOG-SLOT-7F3A";

    private CatalogPrompt() {}

    static String render(String template, String catalog) {
        String probe;
        try {
            probe = template.formatted(MARKER);
        } catch (IllegalFormatException e) {
            throw new IllegalStateException(
                    "system.md is not a template with exactly one %s slot for the catalog: "
                            + e.getMessage() + ". Fix it in the lesson's bundle.", e);
        }

        if (!probe.contains(MARKER)) {
            throw new IllegalStateException(
                    "system.md has no slot the catalog would fill — it needs one %s that is not"
                            + " escaped as %%s. Without it every query would be resolved against"
                            + " nothing. Fix it in the lesson's bundle.");
        }

        return template.formatted(catalog);
    }
}
