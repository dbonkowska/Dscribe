package io.github.dbonkowska.dscribe.labs.s01e02;

/**
 * One candidate, read back out of s01e01's answer file.
 *
 * <p>Declares only the fields this lesson needs rather than reusing {@code s01e01.Person}: that
 * runner has earned its flag and is never revisited, and depending on its types would freeze it
 * permanently. Lessons couple through JSON files instead.
 */
public record Suspect(String name, String surname, int born) {}