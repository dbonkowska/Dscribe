package io.github.dbonkowska.dscribe.labs.s01e05;

/**
 * What the model reports when it has finished, and the schema of the terminal tool's arguments.
 *
 * <p>Unusual among these lessons in that it submits nothing. The result arrives inside an
 * ordinary API response rather than being something the run composes, so the terminal tool is
 * only how the model says "there it is" — the run ends on the model's word, not on the hub's
 * verdict.
 *
 * <p>Which is exactly why the runner does not take that word for it. A model can write a
 * plausible-looking result it never saw, so what it reports is checked against the responses the
 * client actually received before the run calls itself finished.
 */
public record Found(String flag) {}
