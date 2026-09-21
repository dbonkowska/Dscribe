package io.github.dbonkowska.dscribe.labs.s03e03;

/**
 * How a run ended, as the runner's observer decided it from a reply.
 *
 * <p>Two cases rather than one value read two ways: a run that ends in a failure has to be reported
 * as that, and not as a result that happens to be empty.
 */
sealed interface Outcome {

    /** A reply carried the result. {@code text} is exactly what the hub wrote. */
    record Flag(String text) implements Outcome {}

    /** A reply said the run failed and cannot go on. {@code reply} is the whole reply. */
    record Crash(String reply) implements Outcome {}
}
