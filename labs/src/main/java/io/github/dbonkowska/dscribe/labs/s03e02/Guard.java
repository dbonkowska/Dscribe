package io.github.dbonkowska.dscribe.labs.s03e02;

import java.util.List;
import java.util.Optional;

/**
 * Refuses a command that addresses a forbidden root, before it is sent.
 *
 * <p>This is not a sandbox. It reads the text of one command, so a root reached through a
 * {@code cd ..} chain, a glob or a variable gets past it. What it removes is the plain mistake — a
 * command that names the root outright — which is the one a model makes when it has not noticed the
 * rule, and the one whose refusal it can act on.
 */
final class Guard {

    /** Whitespace and the punctuation that separates one shell word from the next. */
    private static final String WORD_BREAKS = "[\\s;|&<>()'\"`]+";

    private final List<String> roots;

    Guard(List<String> roots) {
        this.roots = List.copyOf(roots);
    }

    /**
     * @return the configured root the command addresses, or empty. A root matches a whole path
     *         segment, case-insensitively — never a substring, or {@code zoned} would be refused for
     *         a rule about {@code zone}.
     */
    Optional<String> violated(String command) {
        for (String word : Commands.normalise(command).split(WORD_BREAKS)) {
            for (String segment : word.split("/")) {
                for (String root : roots) {
                    if (segment.equalsIgnoreCase(root)) {
                        return Optional.of(root);
                    }
                }
            }
        }
        return Optional.empty();
    }
}
