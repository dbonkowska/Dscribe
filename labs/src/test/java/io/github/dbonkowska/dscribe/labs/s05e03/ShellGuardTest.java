package io.github.dbonkowska.dscribe.labs.s05e03;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only thing between a command the model wrote and a shell that runs whatever it is given. It
 * accepts what it can identify as read-only and refuses everything else, so the cases below are of two
 * kinds: commands a model reading files will write, which must pass, and every way the shell allows of
 * getting a write or a second command past a check that looks only at the first word.
 *
 * <p>The refusals put the bad part second wherever there is a second place: a check that reads only
 * the first command of a chain passes the first-position version and nothing else. Paths and file
 * names are invented.
 */
class ShellGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "ls -la /srv",
            "grep -n 'a|b;c>d' f.csv",
            "grep \"x y\" f | sort | uniq -c",
            "head -n 5 f; wc -l f",
            "find /srv -name '*.json' 2>/dev/null",
            "jq -r '.[0].name' f.json",
            "cat f 2>&1 | tail -n 3",
            "tail -n 20 f",
            "uniq f",
            "uniq -f 1 f",
            "grep -- -delete f",
            "sort -n f",
            "sort -t ';' -k 2 f",
            "grep -c x f && wc -c f || pwd",
            "cut -d ';' -f 1,3 f | tr -d '\\r'",
            "grep 'it''s' f",
            "zcat f.gz | head -n 2",
            "stat f; basename /srv/f; dirname /srv/f",
            "grep \"\\$HOME\" f",
            "ls;",
            "grep 'x$' f",
            "grep \"x$\" f",
            "grep x$ f",
            "grep -c a f \\\n| wc -l",
            "sort --reverse f",
    })
    void passesAReadOnlyCommand(String command) {
        assertDoesNotThrow(() -> ShellGuard.check(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ls; rm x",
            "cat f | tee g",
            "grep a f && sudo rm x",
            "head f || sed -i s/a/b/ f",
            "ls\nrm x",
    })
    void refusesAForbiddenCommandAnywhereInAChain(String command) {
        assertRefused(command, "is not one of the commands allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "grep `id` f",
            "grep \"$(rm x)\" f",
            "grep \"`id`\" f",
            "grep $(id) f",
            "cat <(ls)",
            "ls >(cat)",
    })
    void refusesSubstitution(String command) {
        assertRefused(command, "substitution");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cat f > g", "cat f >> g", "sort < f", "ls &> g", "ls 2> g", "ls 2>/dev/nullx", "ls 1>/dev/null"})
    void refusesARedirect(String command) {
        assertRefused(command, "redirect");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ls & rm x",
            "X=1 ls",
            "/bin/ls",
            "grep 'unterminated f",
            "grep \"unterminated f",
            "echo hi",
            "printf x",
            "awk 1 f",
            "(rm x)",
            "",
            "  ",
    })
    void refusesEverythingElseThatIsNotAnAllowedCommand(String command) {
        assertRefused(command, "Nothing was sent");
    }

    /**
     * The shell expands these before the command runs, so the guard and the shell would disagree about
     * what the words are: {@code find .${IFS}-delete} is one word to a reader that keeps {@code $}, and
     * {@code find . -delete} to the shell. A {@code $} anywhere but at the end of a word is refused.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "find .${IFS}-delete",
            "find . -$X",
            "find . \"$X\"",
            "find . $'-delete'",
            "find . $\"-delete\"",
            "grep $1 f",
            "grep $@ f",
    })
    void refusesAnExpansionItCannotSeeThrough(String command) {
        assertRefused(command, "expansion");
    }

    /** A backslash before a newline is deleted by the shell, joining the two halves into one word. */
    @ParameterizedTest
    @ValueSource(strings = {"find . -ex\\\nec cat {} \\;", "find . \"-del\\\nete\""})
    void readsALineContinuationAsTheShellDoes(String command) {
        assertRefused(command, "find -");
    }

    /** Each case names the rule that must refuse it, so a rule removed can't hide behind another one. */
    @ParameterizedTest
    @CsvSource(delimiter = '#', quoteCharacter = '"', value = {
            "find . -delete # find -delete",
            "find . -exec cat {} ; # find -exec",
            "find . -execdir cat {} + # find -execdir",
            "find . -ok cat {} ; # find -ok",
            "find . -fprint g # find -fprint",
            "find . '-delete' # find -delete",
            "sort -o g f # sort -o",
            "sort -no g f # sort -no",
            "sort --output=g f # sort --output=g",
            "sort --output g f # sort --output",
            "sort --out=g f # sort --out=g",
            "tail -f f # tail -f",
            "tail -F f # tail -F",
            "tail --follow f # tail --follow",
            "tail --fol f # tail --fol",
            "tail -n 5 -f f # tail -f",
            "uniq f g # uniq with a second file",
            "ls; uniq -c f g # uniq with a second file",
    })
    void refusesAnOptionThatWritesOrNeverReturns(String command, String rule) {
        assertRefused(command, rule);
    }

    /** The model reads this and tries again, so it has to say what the guard does accept. */
    @Test
    void namesWhatIsAllowedWhenItRefuses() {
        String message = assertThrows(IllegalArgumentException.class, () -> ShellGuard.check("ls; rm x")).getMessage();

        assertTrue(message.contains("rm"), message);
        assertTrue(message.contains("Nothing was sent"), message);
        assertTrue(message.contains("grep") && message.contains("jq"), message);
        assertTrue(message.contains("2>/dev/null"), message);
    }

    /** Refused, saying nothing was sent, and for the reason given rather than for some other one. */
    private static void assertRefused(String command, String reason) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> ShellGuard.check(command));
        assertTrue(thrown.getMessage().contains("Nothing was sent"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(reason), () -> "expected the reason '" + reason + "': " + thrown.getMessage());
    }
}
