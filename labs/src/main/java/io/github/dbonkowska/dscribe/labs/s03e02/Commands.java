package io.github.dbonkowska.dscribe.labs.s03e02;

/** Text helpers over a command as the model wrote it. */
final class Commands {

    private Commands() {}

    /**
     * Trims, collapses whitespace runs, collapses repeated slashes and drops a leading {@code ./}.
     * Two spellings of one command come out identical, which is what lets the guard and the
     * no-progress check treat them as one.
     */
    static String normalise(String command) {
        return command.strip()
                .replaceAll("\\s+", " ")
                .replaceAll("/{2,}", "/")
                .replaceAll("(^|[\\s/])\\./", "$1");
    }
}
