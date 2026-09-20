package io.github.dbonkowska.dscribe.llm;

/**
 * Somewhere the raw halves of a model exchange accumulate. The library announces what it sent
 * and what came back; where that goes, and whether anything keeps it, is the application's
 * business — {@code llm-core} never touches a filesystem.
 */
public interface Transcript {

    void append(String request, String response);

    /**
     * What the exchange cost, once the provider has said so.
     *
     * <p>Separate from {@link #append} because the two answer to different rules. The raw halves
     * are announced before the status check, since a rejection is the exchange most worth
     * keeping; usage exists only on a response that parsed, because a rejected call spent
     * nothing.
     *
     * <p>{@code default} rather than abstract, and that is load-bearing: a lambda implements an
     * interface with exactly one abstract method, so a second one would stop {@link #NONE} — and
     * every other {@code (request, response) -> {}} in the codebase — from compiling. The cost is
     * that an implementation written as a lambda silently inherits this no-op, which is a real
     * way to lose a delegated call's spend.
     *
     * @param model the model that actually answered, which is not always the one asked for
     */
    default void usage(String model, Usage usage) {}

    /** Keeps nothing. The default, so recording is opt-in rather than mandatory. */
    Transcript NONE = (request, response) -> {};
}
