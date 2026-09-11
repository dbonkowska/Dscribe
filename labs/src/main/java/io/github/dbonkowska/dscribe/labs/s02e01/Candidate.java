package io.github.dbonkowska.dscribe.labs.s02e01;

/**
 * The whole schema the model sees: the prompt it wants evaluated.
 *
 * <p>One free-text field, which is unusual here — most lessons narrow a field until a wrong value
 * is unrepresentable. That lever is unavailable when the field *is* the thing being designed. What
 * takes its place is everything around it: the model chooses the text and nothing else, while
 * fetching, rendering, ordering, when to stop and cleaning up afterwards are all decided here.
 */
record Candidate(String template) {}
