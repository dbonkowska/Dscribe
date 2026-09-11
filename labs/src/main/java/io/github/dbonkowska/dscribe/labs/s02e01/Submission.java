package io.github.dbonkowska.dscribe.labs.s02e01;

/**
 * What travels to the hub under {@code answer} — once per row, and once more to clean up.
 *
 * <p>The same shape carries both, because the reset is not a different kind of request: it is an
 * ordinary submission whose text happens to mean "forget the previous ones".
 */
record Submission(String prompt) {}
