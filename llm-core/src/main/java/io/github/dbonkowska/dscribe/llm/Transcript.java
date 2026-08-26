package io.github.dbonkowska.dscribe.llm;

/**
 * Somewhere the raw halves of a model exchange accumulate. The library announces what it sent
 * and what came back; where that goes, and whether anything keeps it, is the application's
 * business — {@code llm-core} never touches a filesystem.
 */
public interface Transcript {

    void append(String request, String response);

    /** Keeps nothing. The default, so recording is opt-in rather than mandatory. */
    Transcript NONE = (request, response) -> {};
}
