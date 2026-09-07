package io.github.dbonkowska.dscribe.labs.s01e04;

/**
 * What the model fills in, and the schema of the terminal tool's arguments.
 *
 * <p>One component per slot of the declaration template, named the same, in the same order — the
 * record is what binds the two together. {@link io.github.dbonkowska.dscribe.schema.SchemaUtils}
 * turns these names into the schema the model must satisfy, and the same names are the keys the
 * template is filled through, so the model cannot be asked for a field the document has no place
 * for, nor the document left with a slot nothing fills.
 *
 * <p>There is no {@code date}: the model has no reliable idea what today is, so the runner stamps
 * that slot itself.
 *
 * <p>Every component is a String because the document is text. A number typed as {@code int} here
 * would be rendered by Java's rules rather than the form's, and the form is checked literally.
 */
public record Declaration(
        String origin,
        String sender,
        String destination,
        String route,
        String category,
        String contents,
        String mass,
        String wdp,
        String remarks,
        String amount) {}
