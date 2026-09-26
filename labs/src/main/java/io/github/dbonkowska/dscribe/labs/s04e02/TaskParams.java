package io.github.dbonkowska.dscribe.labs.s04e02;

import tools.jackson.core.JsonPointer;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound from {@code {labs.lessons.dir}/s04e02/task.properties}.
 *
 * <p>The code knows the shape of the exchange — read the help, open a window, queue jobs, collect
 * them, ask the model, sign, configure, finish — and not one word of its vocabulary. Every action,
 * every field the protocol uses, and every closed vocabulary is here. The fields a config point has
 * are not here either: the help reply lists them, and {@code protocol.signFields} and
 * {@code protocol.configFields} say where.
 *
 * <p>Every check refuses at the binding boundary. A mistake found inside the service window is
 * found with the clock already running, and costs the attempt.
 *
 * @param verifyTask     the task name the hub expects
 * @param flagPattern    a regex matching the reply that carries the result
 * @param actions        the action names the runner sends
 * @param documentation  the job the {@code get} action answers directly, read before the window
 * @param jobs           the queued jobs, in the order their results are handed to the model
 * @param protocol       the names of the fields the protocol uses
 * @param slotFields     the point fields a batch entry is filed under, joined with a space
 * @param enums          closed vocabularies: a point field to the values it may take
 * @param pendingCode    the code a collect reply carries when nothing is ready yet
 * @param pollIntervalMs the wait between two collects that found nothing
 * @param safetyMarginMs how long before the window closes the run treats it as closed
 */
public record TaskParams(
        String verifyTask,
        String flagPattern,
        Actions actions,
        String documentation,
        List<String> jobs,
        Protocol protocol,
        List<String> slotFields,
        Map<String, List<String>> enums,
        Integer pendingCode,
        Long pollIntervalMs,
        Long safetyMarginMs) {

    public TaskParams {
        require(verifyTask, "verifyTask");
        require(flagPattern, "flagPattern");
        compiles(flagPattern, "flagPattern");

        if (actions == null) {
            throw missing("actions.help, actions.start, ...");
        }
        require(actions.help(), "actions.help");
        require(actions.start(), "actions.start");
        require(actions.get(), "actions.get");
        require(actions.getResult(), "actions.getResult");
        require(actions.config(), "actions.config");
        require(actions.sign(), "actions.sign");
        require(actions.done(), "actions.done");

        require(documentation, "documentation");
        jobs = nonEmpty(jobs, "jobs");
        // Each document fills the placeholder that bears its name, and each job is collected once:
        // a repeated name either overwrites a document in the prompt or collects a job twice.
        Set<String> names = new HashSet<>();
        for (String job : jobs) {
            if (job.equals(documentation.strip())) {
                throw new IllegalStateException(
                        "jobs lists " + job + ", which is also the documentation's name: its result would"
                                + " replace the documentation in user.md. Rename one in the lesson's"
                                + " task.properties.");
            }
            if (!names.add(job)) {
                throw new IllegalStateException(
                        "jobs lists " + job + " twice: it would be queued twice and its second result"
                                + " refused inside the window. Fix it in the lesson's task.properties.");
            }
        }

        if (protocol == null) {
            throw missing("protocol.actionField, protocol.paramField, ...");
        }
        require(protocol.actionField(), "protocol.actionField");
        require(protocol.paramField(), "protocol.paramField");
        if (protocol.paramField().equals(protocol.actionField())) {
            throw new IllegalStateException(
                    "protocol.paramField is the same as protocol.actionField (" + protocol.actionField()
                            + "): one request carries both, and one of the two values would be lost."
                            + " Fix it in the lesson's task.properties.");
        }
        require(protocol.codeField(), "protocol.codeField");
        require(protocol.sourceField(), "protocol.sourceField");
        require(protocol.echoField(), "protocol.echoField");
        require(protocol.signatureField(), "protocol.signatureField");
        require(protocol.timeoutField(), "protocol.timeoutField");
        require(protocol.batchField(), "protocol.batchField");
        pointer(protocol.signFields(), "protocol.signFields");
        pointer(protocol.configFields(), "protocol.configFields");

        slotFields = nonEmpty(slotFields, "slotFields");

        // No vocabulary is a legitimate file; an empty one is not — it is an enum nothing satisfies.
        Map<String, List<String>> vocabularies = new LinkedHashMap<>();
        if (enums != null) {
            enums.forEach((field, values) -> vocabularies.put(field, nonEmpty(values, "enums." + field)));
        }
        enums = Map.copyOf(vocabularies);

        // Boxed, so an unwritten number binds to null and is refused, instead of binding to zero
        // and passing for a real value.
        if (pendingCode == null) {
            throw missing("pendingCode");
        }
        if (pollIntervalMs == null) {
            throw missing("pollIntervalMs");
        }
        if (pollIntervalMs <= 0) {
            throw new IllegalStateException(
                    "pollIntervalMs must be positive, not " + pollIntervalMs + ": a run that never"
                            + " waits polls the hub as fast as the network allows. Fix it in the"
                            + " lesson's task.properties.");
        }
        if (safetyMarginMs == null) {
            throw missing("safetyMarginMs");
        }
        if (safetyMarginMs < 0) {
            throw new IllegalStateException(
                    "safetyMarginMs must not be negative, not " + safetyMarginMs + ": it would plan"
                            + " to finish after the window closes. Fix it in the lesson's"
                            + " task.properties.");
        }
    }

    /** Blank entries are dropped first, so a list of only blanks is refused as empty. */
    private static List<String> nonEmpty(List<String> values, String key) {
        List<String> kept = values == null
                ? List.of()
                : values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
        if (kept.isEmpty()) {
            throw new IllegalStateException(
                    key + " has no values. Set " + key + ".1=..., " + key + ".2=... in the lesson's"
                            + " task.properties.");
        }
        return kept;
    }

    /** A pointer that does not start with a slash is a path into nothing, and reads as absent. */
    private static void pointer(String value, String key) {
        require(value, key);
        try {
            JsonPointer.compile(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    key + " is not a JSON pointer: \"" + value + "\". It starts with / and names each"
                            + " step into the help reply, e.g. /a/b/c. Fix it in the lesson's"
                            + " task.properties.", e);
        }
    }

    /**
     * Compiled here rather than where it is applied, so a typo fails before anything is spent: a
     * pattern that throws on the last reply would discard a result the hub had already given.
     */
    private static void compiles(String pattern, String key) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException(
                    key + " is not a valid regex: " + e.getDescription() + ". Remember every backslash is"
                            + " doubled in a properties file.", e);
        }
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    key + " is missing or blank. A string key nobody wrote binds to null rather"
                            + " than failing, and this one would fail later and further from"
                            + " here. Set it in the lesson's task.properties.");
        }
    }

    private static IllegalStateException missing(String key) {
        return new IllegalStateException(key + " is missing. Set it in the lesson's task.properties.");
    }

    /** The action names the runner sends. */
    public record Actions(
            String help, String start, String get, String getResult, String config, String sign, String done) {}

    /**
     * The names of the fields the protocol uses.
     *
     * @param actionField    the field of a request naming its action
     * @param paramField     the field of a {@code get} request naming its job
     * @param codeField      the field of a reply carrying its status code
     * @param sourceField    the field of a collected result naming the job that produced it
     * @param echoField      the field of a signing result echoing what it signed
     * @param signatureField the field carrying a signature, in a result and in a config point
     * @param timeoutField   the field of the start reply giving the window's length in seconds
     * @param batchField     the field of a config request holding several points
     * @param signFields     where in the help reply the signing action's fields are listed
     * @param configFields   where in the help reply a single config point's fields are listed
     */
    public record Protocol(
            String actionField, String paramField, String codeField, String sourceField, String echoField,
            String signatureField, String timeoutField, String batchField, String signFields,
            String configFields) {}
}
