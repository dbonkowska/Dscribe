package io.github.dbonkowska.dscribe.labs.s03e02;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refuses a command that addresses a forbidden path, before it is sent.
 *
 * <p>Two kinds of path are forbidden. The roots bound at start-up are known before the run begins.
 * The others are only named in a file the run finds while it works — a list of what must not be
 * touched — so the guard reads them out of the replies that carry such a file ({@link #learn}) and
 * refuses them from then on.
 *
 * <p>This is not a sandbox. It reads the text of one command, so a path reached through a
 * {@code cd ..} chain, a variable or a glob the shell expands gets past it. What it removes is the
 * plain mistake — a command that names the path outright — which is the one a model makes when it
 * has not noticed the rule, and the one whose refusal it can act on.
 */
final class Guard {

    /**
     * How a reply is recognised as a file listing paths that are off limits.
     *
     * @param file       the file's name, matched against the last segment of the path that was read
     * @param pathKey    the reply field holding the path that was read
     * @param contentKey the reply field holding the file's content
     */
    record Learning(String file, String pathKey, String contentKey) {}

    /**
     * One entry of a learned listing.
     *
     * @param dir      the segments of the directory the listing sits in, which the entry is relative to
     * @param entry    the entry as written, one string per segment, for showing
     * @param patterns the same segments as matchers, so a wildcard can stand for part of a name
     * @param file     the listing the entry came from, so a refusal can say where the rule was found
     */
    private record Rule(List<String> dir, List<String> entry, List<Pattern> patterns, String file) {

        String path() {
            List<String> all = new ArrayList<>(dir);
            all.addAll(entry);
            return "/" + String.join("/", all);
        }

        String describe() {
            return path() + " (listed in " + file + ")";
        }

        /**
         * An absolute path is compared whole — the entry is only forbidden inside the directory its
         * listing sits in, so the same name elsewhere is not caught. A relative one cannot be placed,
         * since the working directory is unknown, so it is refused wherever the entry's segments
         * appear in it, which errs towards refusing.
         */
        boolean matches(String word) {
            boolean absolute = word.startsWith("/");
            List<String> segments = segments(word, absolute);

            if (absolute) {
                return inDir(segments) && matchesFrom(segments, dir.size());
            }
            for (int start = 0; start + patterns.size() <= segments.size(); start++) {
                if (matchesFrom(segments, start)) {
                    return true;
                }
            }
            return false;
        }

        private boolean inDir(List<String> segments) {
            if (segments.size() < dir.size()) {
                return false;
            }
            for (int i = 0; i < dir.size(); i++) {
                if (!segments.get(i).equalsIgnoreCase(dir.get(i))) {
                    return false;
                }
            }
            return true;
        }

        private boolean matchesFrom(List<String> segments, int start) {
            if (segments.size() < start + patterns.size()) {
                return false;
            }
            for (int i = 0; i < patterns.size(); i++) {
                if (!patterns.get(i).matcher(segments.get(start + i)).matches()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Whitespace and the punctuation that separates one shell word from the next. */
    private static final String WORD_BREAKS = "[\\s;|&<>()'\"`]+";

    private final List<String> roots;
    private final Learning learning;

    private final List<Rule> learned = new ArrayList<>();
    private final Set<String> known = new HashSet<>();

    Guard(List<String> roots) {
        this(roots, null);
    }

    /** @param learning how to recognise a listing in a reply, or null to learn nothing */
    Guard(List<String> roots, Learning learning) {
        this.roots = List.copyOf(roots);
        this.learning = learning;
    }

    /**
     * Reads the forbidden paths out of a reply, if it is the content of a listing file. Anything
     * else is left alone: replies are arbitrary text, and most of them are not listings.
     *
     * <p>Comment lines, blank lines and negations are skipped. A wildcard stands for part of one name
     * and never crosses a slash. An entry is taken relative to the directory the listing sits in.
     */
    void learn(String reply) {
        if (learning == null) {
            return;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(reply);
        } catch (JacksonException e) {
            return;
        }
        JsonNode path = node.get(learning.pathKey());
        JsonNode content = node.get(learning.contentKey());
        if (path == null || !path.isString() || content == null || !content.isString()) {
            return;
        }

        List<String> where = segments(path.stringValue(), true);
        if (where.isEmpty() || !where.getLast().equals(learning.file())) {
            return;
        }
        List<String> dir = List.copyOf(where.subList(0, where.size() - 1));

        content.stringValue().lines().map(String::strip).forEach(line -> {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                return;
            }
            List<String> entry = segments(line, false);
            if (entry.isEmpty()) {
                return;
            }
            Rule rule = new Rule(dir, entry, entry.stream().map(Guard::glob).toList(), learning.file());
            if (known.add(rule.path())) {
                learned.add(rule);
            }
        });
    }

    /**
     * @return the forbidden path the command addresses, or empty. A configured root matches a whole
     *         path segment, case-insensitively — never a substring, or {@code zoned} would be refused
     *         for a rule about {@code zone}. A learned path is described as it was resolved, with the
     *         file that listed it.
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
            for (Rule rule : learned) {
                if (rule.matches(word)) {
                    return Optional.of(rule.describe());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The segments of a path with {@code .} dropped and {@code ..} resolved. Above the root of an
     * absolute path it is dropped; at the start of a relative one it is kept, since it names a
     * directory the command's own text cannot place.
     */
    private static List<String> segments(String path, boolean absolute) {
        List<String> out = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!out.isEmpty() && !out.getLast().equals("..")) {
                    out.removeLast();
                } else if (!absolute) {
                    out.add(segment);
                }
                continue;
            }
            out.add(segment);
        }
        return out;
    }

    /** One name as a matcher: {@code *} and {@code ?} stand for part of it, everything else is literal. */
    private static Pattern glob(String name) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? "[^/]*" : "[^/]");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
