package io.github.dbonkowska.dscribe.labs.s03e02;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The guard refuses a command that addresses a forbidden root, and lets through one that merely
 * contains the root's letters. The second half is the one a lazy implementation breaks: matching by
 * substring refuses {@code ls /data/zoned} for a rule about {@code zone}, and the model is then told
 * it cannot do something it may do.
 *
 * <p>Roots are invented: nothing in this file is supplied by the exercise.
 */
class GuardTest {

    private final Guard guard = new Guard(List.of("zone", "vault"));

    @ParameterizedTest
    @CsvSource(delimiter = '=', textBlock = """
            ls zone                        = zone
            cat /zone/a.txt                = zone
            cat ./zone/a.txt               = zone
            cat ../zone/a.txt              = zone
            echo hi && cat vault/x | head  = vault
            ls   /zone//a                  = zone
            LS /ZONE                       = zone
            """)
    void refusesACommandThatAddressesAForbiddenRoot(String command, String root) {
        assertEquals(Optional.of(root), guard.violated(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ls /data/zoned",
            "cat ozone/notes",
            "ls /zone-free/a",
            "cat vault.txt",
            "ls -la /data"})
    void permitsACommandThatOnlyContainsARootsName(String command) {
        assertEquals(Optional.empty(), guard.violated(command));
    }
}
