package io.github.dbonkowska.dscribe.labs.s02e03;

import java.time.LocalDateTime;

/**
 * One line of the source as it was read, before anything is merged.
 *
 * @param number   1-based position in the source, blank lines included — the number a person
 *                 opening the file would see
 * @param minute   when it happened, to the minute: the finest resolution a submitted line carries
 * @param severity the level the line was logged at, as written
 * @param message  everything after the severity, as written
 * @param raw      the whole line unchanged, which is what zoom shows
 */
record Line(int number, LocalDateTime minute, String severity, String message, String raw) {}
