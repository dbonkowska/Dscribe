package io.github.dbonkowska.dscribe.labs.s02e03;

/**
 * What the model reports when it has finished, and the schema of the terminal tool's arguments.
 *
 * <p>Submits nothing: the result arrives inside a reply a submission already received, so this is
 * only how the model says "there it is". Which is why the runner does not take its word — a model
 * can write a plausible result it never saw.
 *
 * <p>A copy rather than a reuse. Lessons couple through JSON files, never through each other's
 * Java types — importing s02e02's would freeze that lesson's record permanently.
 */
public record Found(String flag) {}
