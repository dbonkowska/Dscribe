package io.github.dbonkowska.dscribe.labs.s05e03;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Accepts a shell command only when it can identify every part of it as read-only, and refuses
 * everything else before it is sent.
 *
 * <p>Positive rather than a list of what is dangerous. A shell has more ways to write than anyone
 * would think to list, and they are not the obvious ones. A deny-list is a guess at every way to write,
 * and the one it missed goes straight through; this asks only "is each command one of the few that
 * read?".
 *
 * <p>The command is split the way the shell would split it: single quotes are literal, double quotes
 * are literal except for substitution, a backslash before a newline is deleted, and {@code |},
 * {@code ||}, {@code &&}, {@code ;} and newlines separate commands. Whatever would make the shell's
 * words differ from the ones read here is refused rather than modelled: every {@code $} expansion and
 * every substitution. Then <em>every</em> command's first word must be in {@link #ALLOWED}, compared
 * exactly, and the few allowed commands that have a writing or never-ending option are checked for it.
 *
 * <p>This is not a sandbox and does not pretend to be. It takes the plain mistake out of the model's
 * hands for the price of one refused call, and the shell's own permissions stay the real enforcer.
 * The list is shell vocabulary, the same for any task, so it lives here rather than in the bundle —
 * and it holds only what the probe found installed, since a refusal that recommends a command which
 * then answers {@code not found} sends the model the wrong way.
 */
final class ShellGuard {

    /**
     * The commands that read and do nothing else. {@code echo} and {@code printf} are left off on
     * purpose: printing the answer is the report tool's job, and keeping them off keeps the answer on
     * one channel. {@code sed}, {@code awk} and {@code xargs} are off because each can write or run
     * another command.
     */
    static final List<String> ALLOWED = List.of(
            "ls", "find", "cat", "head", "tail", "grep", "egrep", "fgrep", "zcat", "wc", "sort", "uniq",
            "cut", "tr", "jq", "stat", "pwd", "basename", "dirname");

    private static final Set<String> ALLOWED_SET = Set.copyOf(ALLOWED);

    /** The redirects a reading command needs, both of which only discard or merge error output. */
    private static final List<String> HARMLESS_REDIRECTS = List.of(">/dev/null", ">&1");

    /** {@code find}'s actions that delete, run another command, or write a file. */
    private static final Set<String> FIND_ACTIONS = Set.of(
            "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls");

    /** {@code uniq}'s options that take their value as the next word, which is then not a file. */
    private static final Set<String> UNIQ_VALUED = Set.of("-f", "-s", "-w");

    /**
     * What may follow a {@code $} that stands for itself, as in the anchor of {@code grep x$ f}. Anything
     * else is refused rather than listed: the shell has more expansions than a list of them would hold
     * ({@code $X}, {@code ${IFS}}, {@code $1}, {@code $@}, {@code $'...'}, {@code $"..."}), and each one
     * makes the words read here different from the words the shell runs.
     */
    private static final String PLAIN_WORD_END = " \t\n\r;|&";

    /** The same inside double quotes, where the closing quote ends the word and {@code ;} is plain text. */
    private static final String QUOTED_WORD_END = " \t\n\"";

    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*", Pattern.DOTALL);

    private ShellGuard() {}

    /**
     * Returns normally for a command it accepts.
     *
     * @throws IllegalArgumentException naming the reason and what is accepted, for any other command
     */
    static void check(String command) {
        List<List<String>> commands = split(command);
        if (commands.isEmpty()) {
            throw refused("the command is empty");
        }
        for (List<String> words : commands) {
            String name = words.getFirst();
            if (ASSIGNMENT.matcher(name).matches()) {
                throw refused("a variable assignment (" + name + ") is not a command this shell runs for you");
            }
            if (!ALLOWED_SET.contains(name)) {
                throw refused(name + " is not one of the commands allowed here");
            }
            List<String> arguments = words.subList(1, words.size());
            switch (name) {
                case "find" -> refuseFindActions(arguments);
                case "sort" -> refuseSortOutput(arguments);
                case "tail" -> refuseTailFollow(arguments);
                case "uniq" -> refuseUniqOutput(arguments);
                default -> {}
            }
        }
    }

    private static void refuseFindActions(List<String> arguments) {
        for (String argument : arguments) {
            if (FIND_ACTIONS.contains(argument)) {
                throw refused("find " + argument + " deletes, writes or runs another command");
            }
        }
    }

    /**
     * A short option word naming {@code o} anywhere ({@code -o}, {@code -no}) writes the output to a
     * file. Refusing a {@code -t o} separator along with it costs a call; letting {@code -no} through
     * would not be noticed at all.
     */
    private static void refuseSortOutput(List<String> arguments) {
        for (String argument : options(arguments)) {
            if (isLongOption(argument, "--output")
                    || (isShortOption(argument) && argument.indexOf('o') > 0)) {
                throw refused("sort " + argument + " writes its output to a file");
            }
        }
    }

    /** Following a file never returns, so the call would hang until the hub gave up on it. */
    private static void refuseTailFollow(List<String> arguments) {
        for (String argument : options(arguments)) {
            if (isLongOption(argument, "--follow")
                    || (isShortOption(argument) && (argument.indexOf('f') > 0 || argument.indexOf('F') > 0))) {
                throw refused("tail " + argument + " follows the file and never returns");
            }
        }
    }

    /** {@code uniq}'s second file is where it writes, not a second input. */
    private static void refuseUniqOutput(List<String> arguments) {
        int files = 0;
        boolean endOfOptions = false;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (!endOfOptions && argument.equals("--")) {
                endOfOptions = true;
            } else if (!endOfOptions && UNIQ_VALUED.contains(argument)) {
                i++;
            } else if (endOfOptions || !argument.startsWith("-") || argument.equals("-")) {
                files++;
            }
        }
        if (files > 1) {
            throw refused("uniq with a second file writes its output to that file");
        }
    }

    /** The words before a {@code --}: after it, everything is a file name, however it is spelled. */
    private static List<String> options(List<String> arguments) {
        int end = arguments.indexOf("--");
        return end < 0 ? arguments : arguments.subList(0, end);
    }

    private static boolean isShortOption(String word) {
        return word.length() > 1 && word.charAt(0) == '-' && word.charAt(1) != '-';
    }

    /**
     * The option, with or without {@code =value}, under any abbreviation: some implementations accept an
     * unambiguous prefix, so {@code --out=g} is {@code --output=g} there, and the guard should not rest on
     * which one is installed.
     */
    private static boolean isLongOption(String word, String option) {
        int equals = word.indexOf('=');
        String name = equals < 0 ? word : word.substring(0, equals);
        return name.length() > 2 && option.startsWith(name);
    }

    /**
     * Splits the command into commands of words, the way the shell would, refusing every construct the
     * guard cannot see through: substitution, a {@code $} expansion, a redirect, a background job, a
     * subshell, an unterminated quote. Empty commands (a trailing {@code ;}) are dropped.
     */
    private static List<List<String>> split(String command) {
        List<List<String>> commands = new ArrayList<>();
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        int n = command.length();
        while (i < n) {
            char c = command.charAt(i);
            switch (c) {
                case ' ', '\t' -> {
                    inWord = endWord(words, word, inWord);
                    i++;
                }
                case '\'' -> {
                    int close = command.indexOf('\'', i + 1);
                    if (close < 0) {
                        throw refused("a single quote is never closed");
                    }
                    word.append(command, i + 1, close);
                    inWord = true;
                    i = close + 1;
                }
                case '"' -> {
                    i = doubleQuoted(command, i + 1, word);
                    inWord = true;
                }
                case '\\' -> {
                    // a line continuation is deleted, not kept: the halves around it are one word
                    if (i + 1 < n && command.charAt(i + 1) != '\n') {
                        word.append(command.charAt(i + 1));
                        inWord = true;
                    }
                    i += 2;
                }
                case '`' -> throw refused("command substitution with backticks");
                case '$' -> {
                    if (i + 1 < n && command.charAt(i + 1) == '(') {
                        throw refused("command substitution with $(...)");
                    }
                    if (!endsWord(command, i + 1, PLAIN_WORD_END)) {
                        throw refused("a $ expansion");
                    }
                    word.append(c);
                    inWord = true;
                    i++;
                }
                case '(', ')' -> {
                    if (i > 0 && (command.charAt(i - 1) == '<' || command.charAt(i - 1) == '>')) {
                        throw refused("process substitution");
                    }
                    throw refused("a subshell or grouping with ( )");
                }
                case ';', '\n', '\r' -> {
                    inWord = endWord(words, word, inWord);
                    endCommand(commands, words);
                    i++;
                }
                case '|' -> {
                    inWord = endWord(words, word, inWord);
                    endCommand(commands, words);
                    i += (i + 1 < n && command.charAt(i + 1) == '|') ? 2 : 1;
                }
                case '&' -> {
                    if (i + 1 < n && command.charAt(i + 1) == '&') {
                        inWord = endWord(words, word, inWord);
                        endCommand(commands, words);
                        i += 2;
                    } else if (i + 1 < n && command.charAt(i + 1) == '>') {
                        throw refused("a redirect (&>)");
                    } else {
                        throw refused("a background job with &");
                    }
                }
                case '<' -> {
                    if (i + 1 < n && command.charAt(i + 1) == '(') {
                        throw refused("process substitution");
                    }
                    throw refused("a redirect (<)");
                }
                case '>' -> {
                    if (i + 1 < n && command.charAt(i + 1) == '(') {
                        throw refused("process substitution");
                    }
                    String harmless = harmlessRedirect(command, i, word, inWord);
                    if (harmless == null) {
                        throw refused("a redirect (>)");
                    }
                    // the "2" before it was the redirect's descriptor, not an argument
                    word.setLength(0);
                    inWord = false;
                    i += harmless.length();
                }
                default -> {
                    word.append(c);
                    inWord = true;
                    i++;
                }
            }
        }
        endWord(words, word, inWord);
        endCommand(commands, words);
        return commands;
    }

    /**
     * Inside double quotes the shell still runs substitution, so it is refused here too; a backslash
     * escapes the next character. Returns the index just past the closing quote.
     */
    private static int doubleQuoted(String command, int start, StringBuilder word) {
        int i = start;
        while (i < command.length()) {
            char c = command.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c == '`') {
                throw refused("command substitution with backticks, inside double quotes");
            }
            if (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(') {
                throw refused("command substitution with $(...), inside double quotes");
            }
            if (c == '$' && !endsWord(command, i + 1, QUOTED_WORD_END)) {
                throw refused("a $ expansion, inside double quotes");
            }
            if (c == '\\' && i + 1 < command.length()) {
                if (command.charAt(i + 1) != '\n') {
                    word.append(command.charAt(i + 1));
                }
                i += 2;
                continue;
            }
            word.append(c);
            i++;
        }
        throw refused("a double quote is never closed");
    }

    /** Whether the text at {@code at} is the end of the command or one of {@code ends}. */
    private static boolean endsWord(String command, int at, String ends) {
        return at == command.length() || ends.indexOf(command.charAt(at)) >= 0;
    }

    /**
     * The redirect at {@code at}, when it is exactly {@code 2>/dev/null} or {@code 2>&1} and ends where a
     * word would: the text from {@code >} on, or null for any other redirect.
     */
    private static String harmlessRedirect(String command, int at, StringBuilder word, boolean inWord) {
        if (!inWord || !word.toString().equals("2")) {
            return null;
        }
        for (String redirect : HARMLESS_REDIRECTS) {
            int end = at + redirect.length();
            if (command.startsWith(redirect, at) && (end == command.length() || " \t;|&\n\r".indexOf(command.charAt(end)) >= 0)) {
                return redirect;
            }
        }
        return null;
    }

    private static boolean endWord(List<String> words, StringBuilder word, boolean inWord) {
        if (inWord) {
            words.add(word.toString());
            word.setLength(0);
        }
        return false;
    }

    private static void endCommand(List<List<String>> commands, List<String> words) {
        if (!words.isEmpty()) {
            commands.add(List.copyOf(words));
            words.clear();
        }
    }

    private static IllegalArgumentException refused(String reason) {
        return new IllegalArgumentException(
                "Refused: " + reason + ". Nothing was sent. Only these commands run here: "
                        + String.join(", ", ALLOWED) + " — joined with |, ;, && or ||, with no redirect other"
                        + " than 2>/dev/null or 2>&1, and no $ expansions, backticks or subshells.");
    }
}
