package io.github.dbonkowska.dscribe.labs.s03e02;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that grows during a run. Some paths that are off limits are only named in a file the
 * run finds while it works, so no list bound at start-up can hold them: the guard reads them out of
 * the reply that carries the file, and refuses them from then on.
 *
 * <p>The failure this exists for is a single command the model writes in the same turn as the one
 * that reads the file, before it could have seen what the file says. Commands run in the order they
 * were written, so the file has always been read by the time the later one is checked.
 *
 * <p>File names, keys and paths are invented: nothing in this file is supplied by the exercise.
 */
class GuardLearningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Guard.Learning LEARNING = new Guard.Learning(".ign", "path", "data");

    private final Guard guard = new Guard(List.of("zone"), LEARNING);

    /** A reply as the shell would give one: the path that was read, and what was in it. */
    private static String reply(String path, String... lines) {
        return MAPPER.writeValueAsString(Map.of("code", 150, "path", path, "data", String.join("\n", lines)));
    }

    private void learnListing() {
        guard.learn(reply("/srv/app/.ign", "secret.txt", "store/"));
    }

    @Test
    void permitsAnEntryBeforeItsFileHasBeenRead() {
        assertEquals(Optional.empty(), guard.violated("cat /srv/app/secret.txt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "cat /srv/app/secret.txt",
            "cat secret.txt",
            "cat ./app/secret.txt",
            "cat /srv//app/secret.txt",
            "cat /srv/app/x/../secret.txt",
            "ls /srv/app/store",
            "cat /srv/app/store/inner",
            "echo hi && cat /srv/app/secret.txt | head"})
    void refusesAnEntryOnceItsFileHasBeenRead(String command) {
        learnListing();

        assertTrue(guard.violated(command).isPresent(), command);
    }

    /** The refusal has to say which path and where the rule came from, so the model can act on it. */
    @Test
    void namesTheResolvedPathAndTheFileItWasListedIn() {
        learnListing();

        assertEquals(Optional.of("/srv/app/secret.txt (listed in .ign)"), guard.violated("cat secret.txt"));
    }

    /**
     * An absolute path is compared whole, so the same file name in another directory is not caught,
     * and one that merely starts with an entry's letters is not either.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "cat /other/secret.txt",
            "cat /srv/other/secret.txt",
            "cat /tmp/x/secret.txt",
            "cat secrets.txt",
            "cat /srv/app/notes",
            "cat /srv/appsecret.txt",
            "ls /srv/app",
            "ls /srv/app/stored"})
    void permitsWhatOnlyLooksLikeAnEntry(String command) {
        learnListing();

        assertEquals(Optional.empty(), guard.violated(command), command);
    }

    /** Only a file of the configured name is a list of what to avoid. */
    @Test
    void aFileOfAnotherNameTeachesNothing() {
        guard.learn(reply("/srv/app/notes", "secret.txt"));

        assertEquals(Optional.empty(), guard.violated("cat /srv/app/secret.txt"));
    }

    @Test
    void skipsCommentsBlankLinesAndNegations() {
        guard.learn(reply("/srv/app/.ign", "# a note", "", "!keep", "real"));

        assertTrue(guard.violated("cat /srv/app/real").isPresent());
        assertEquals(Optional.empty(), guard.violated("cat /srv/app/keep"));
        assertEquals(Optional.empty(), guard.violated("cat /srv/app/note"));
    }

    /** A wildcard stands for part of one name, never across a slash. */
    @Test
    void understandsAWildcardWithinOneName() {
        guard.learn(reply("/srv/app/.ign", "*.log"));

        assertTrue(guard.violated("cat /srv/app/a.log").isPresent());
        assertTrue(guard.violated("cat a.log").isPresent());
        assertEquals(Optional.empty(), guard.violated("cat /srv/app/a.txt"));
    }

    /** Replies are arbitrary text: anything that is not the expected shape is simply not a listing. */
    @ParameterizedTest
    @ValueSource(strings = {"plain text", "{", "[]", "{\"code\":150}", "{\"path\":\"/srv/app/.ign\"}"})
    void ignoresAReplyItCannotReadAsAListing(String body) {
        guard.learn(body);

        assertEquals(Optional.empty(), guard.violated("cat /srv/app/secret.txt"));
    }

    @Test
    void keepsTheStartUpRootsInForce() {
        learnListing();

        assertEquals(Optional.of("zone"), guard.violated("ls zone"));
    }
}
