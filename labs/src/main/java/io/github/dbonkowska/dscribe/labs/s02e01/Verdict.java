package io.github.dbonkowska.dscribe.labs.s02e01;

/**
 * One cycle's outcome — the model's only view of an attempt.
 *
 * <p>{@code hubMessage} is the body of the response that ended the cycle, verbatim. The judge's own
 * error text is the signal the model revises from, and paraphrasing it here would be this record
 * deciding what the model is allowed to learn.
 *
 * <p>What it deliberately does not carry is any row text. The input rotates between cycles, so
 * anything inferred about a particular row is worth less than nothing — it is a rule that will be
 * wrong next time, learned confidently.
 *
 * @param submitted   rows sent before the cycle ended
 * @param accepted    of those, how many came back without the judge objecting
 * @param hubMessage  the response that ended the cycle, as it arrived
 * @param flag        the result, if this cycle produced one; null otherwise
 */
record Verdict(int submitted, int accepted, String hubMessage, String flag) {}
