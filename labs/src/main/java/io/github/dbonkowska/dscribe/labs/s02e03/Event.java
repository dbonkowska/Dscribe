package io.github.dbonkowska.dscribe.labs.s02e03;

import java.time.LocalDateTime;

/**
 * Every line with the same severity and message, merged into one.
 *
 * @param id       what the model refers to it by, stable for a given source: {@code E} and its
 *                 position in first-occurrence order
 * @param severity the level shared by every line merged here
 * @param message  the words shared by every line merged here
 * @param first    the minute it was first seen — the time a submitted line carries, so events keep
 *                 the order cause and effect happened in
 * @param last     the minute it was last seen, which says whether it was still going when things
 *                 ended
 * @param count    how many lines were merged
 */
record Event(String id, String severity, String message, LocalDateTime first, LocalDateTime last, int count) {}
