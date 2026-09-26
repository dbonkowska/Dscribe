package io.github.dbonkowska.dscribe.llm;

/**
 * How much a model thinks before it answers, as the provider spells it: {@code reasoning.effort}.
 *
 * <p>Exists for models whose reasoning cannot be switched off. Left unset, such a model thinks as
 * long as its provider's default allows, and a caller working to a deadline cannot tell how long a
 * call will take. The value is passed through as written — which efforts a model accepts is the
 * provider's list, not this library's.
 */
public record Reasoning(String effort) {

    public static Reasoning effort(String effort) {
        return new Reasoning(effort);
    }
}
