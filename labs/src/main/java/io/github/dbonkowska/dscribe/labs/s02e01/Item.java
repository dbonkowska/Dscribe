package io.github.dbonkowska.dscribe.labs.s02e01;

/**
 * One row of the fetched input.
 *
 * <p>Lesson-local and staying that way. Rows are never handed to another lesson — the coupling
 * between lessons is a JSON file, never a Java type — and the content rotates between cycles, so
 * nothing here is worth remembering past the cycle that read it.
 */
record Item(String id, String description) {}
