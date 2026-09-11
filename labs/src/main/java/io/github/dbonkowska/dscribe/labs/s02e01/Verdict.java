package io.github.dbonkowska.dscribe.labs.s02e01;

/**
 * One cycle's outcome — the model's only view of an attempt.
 *
 * <p>{@code hubMessage} is the body of the response that ended the cycle, verbatim. The judge's own
 * error text is the signal the model revises from, and paraphrasing it here would be this record
 * deciding what the model is allowed to learn.
 *
 * <p>What it deliberately does not carry is any row text. The input rotates between runs, so
 * anything inferred about a particular row is worth less than nothing — it is a rule that will be
 * wrong next time, learned confidently.
 *
 * <p>Nor does it carry a count of rows that passed, which it used to. The judge reports its own
 * progress inside {@code hubMessage}, and a wrong classification zeroes that counter — so a cycle
 * that failed on row eight showed the model "seven passed" from here and "zero classified" from
 * the judge, in the same breath. Two contradictory progress counts, one of them authoritative.
 * {@code submitted} already says where it stopped.
 *
 * @param submitted  rows sent before the cycle ended; the last of them is the one that ended it
 * @param hubMessage the last response the cycle received, as it arrived — the one that ended it
 *                   where something did, and simply the final row's where every row passed
 * @param flag       the result, if this cycle produced one; null otherwise
 */
record Verdict(int submitted, String hubMessage, String flag) {}
