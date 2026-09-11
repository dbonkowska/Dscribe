package io.github.dbonkowska.dscribe.labs.s02e01;

/**
 * What the model reports when it has finished, and the schema of the terminal tool's arguments.
 *
 * <p>Like s01e05's, this submits nothing — the result arrives inside a response the cycle already
 * received, so the terminal tool is only how the model says "there it is". Which is exactly why
 * the runner does not take its word: a model can write a plausible-looking result it never saw,
 * so what it reports is checked against the responses the client actually collected.
 *
 * <p>A copy rather than a reuse. Lessons couple through JSON files, never through each other's
 * Java types — importing s01e05's would freeze that lesson's record permanently.
 */
public record Found(String flag) {}
