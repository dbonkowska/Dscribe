package io.github.dbonkowska.dscribe.labs.s03e02;

import io.github.dbonkowska.dscribe.agent.Agent;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.tool.Toolbox;

import java.time.Duration;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first lesson whose tool takes a free string the run cannot narrow: a command for a shell the
 * model learns about at start-up. The strongest lever against a model's mistakes — a schema that
 * cannot represent them — is unavailable, so each failure is re-homed one at a time:
 *
 * <ul>
 *   <li>a command that addresses a forbidden path is refused in the tool, before a request is spent;
 *   <li>a refusal the environment answers with is classified, and waited out or not retried;
 *   <li>the result code is read from the recorded replies by the runner, and the submit tool takes
 *       no argument at all;
 *   <li>a run that repeats itself is ended by a count of what it did, not by the iteration cap;
 *   <li>paths that a file found during the run lists as off limits are learned from its reply, so
 *       the guard grows as the run reads, and the prompt is a second line of defence rather than
 *       the only one.
 * </ul>
 *
 * <p>Nothing resets the environment, neither at start-up nor in a {@code finally}. A reset here is a
 * full reboot that would discard a partly-correct configuration, and a tool for it is one the model
 * could reach for without needing it. The operator resets by hand, and the transcript's settings say
 * so, so a run that inherited a broken state points at the run before it.
 */
public class S03E02 {

    /**
     * The lesson's own pin. A newer Sonnet is the first thing to try if the loop stalls, but
     * measurement decides that rather than the release date.
     *
     * <p>A preference, not the last word — {@code -Dopenrouter.model}, {@code OPENROUTER_MODEL} and
     * {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "anthropic/claude-sonnet-4-6";

    /**
     * Counts model round-trips. Generous: exploring an unfamiliar shell takes many rounds, and a cap
     * that ended the run mid-configuration would leave the environment half-changed for the next one.
     * The no-progress count is what is expected to end a stuck run, this is only the backstop.
     */
    private static final int MAX_ITERATIONS = 40;

    /** The ceiling on any single wait: a misread header becomes a wrong-length pause, not a hang. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(2);

    /**
     * How many times in a row one command may get the same reply before a repeat is refused.
     * Mechanism rather than task content, so it lives here and not in the bundle.
     */
    private static final int REPEAT_THRESHOLD = 3;

    /**
     * The most of one shell reply the model is handed. Reading a binary returned it as megabytes of
     * escaped bytes, which no context holds, and every later request would have carried it again. The
     * start and end are kept, and the whole reply stays in the transcript and in what the submit tool
     * reads its code from.
     */
    private static final int MAX_REPLY_CHARS = 6000;

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        Lesson lesson = Lesson.of(labsConfig, "s03e02");
        TaskParams task = lesson.task(TaskParams.class);
        TaskParams.Shell shell = task.shell();

        // Checked here, before a transcript is open and before anything has been spent: a template
        // that cannot carry the shell's description is a run that cannot succeed.
        String userTemplate = requireHelpSlot(lesson.prompt("user.md"));

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max iterations", String.valueOf(MAX_ITERATIONS));
        settings.put("max wait", MAX_WAIT.toString());
        settings.put("repeat threshold", String.valueOf(REPEAT_THRESHOLD));
        settings.put("max reply chars", String.valueOf(MAX_REPLY_CHARS));
        settings.put("shell path", shell.path());
        settings.put("forbidden roots", String.join(", ", task.forbidden()));
        settings.put("environment reset", "none: state is inherited from the previous run, check that run first");

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                "s03e02",
                settings,
                List.of(labsConfig.llm().apiKey(), labsConfig.hub().apiKey()))) {

            HubClient hub = new HubClient(labsConfig.hub(), transcript);
            LlmClient recorded = llm.withTranscript(transcript);

            // Not ResilientHub for the shell: it is built around a verify task and a rate-limit
            // header set, and this endpoint's failure vocabulary is different. It is used for the
            // submission, which does go to verify.
            ResilientHub resilient = new ResilientHub(
                    hub::send,
                    task.verifyTask(),
                    RetryPolicy.defaults(),
                    new RateLimitHeaders(List.of(), MAX_WAIT),
                    Sleeper.real(),
                    transcript);

            // Code's call rather than the model's: nothing is being judged yet, and the reply is what
            // the model needs before its first command. Asking for it would cost a round.
            String help = hub.post("help", shell.path(), Map.of(shell.commandKey(), shell.helpCommand()));

            List<Message> seed = List.of(
                    new Message(Role.system, lesson.prompt("system.md")),
                    new Message(Role.user, userTemplate.formatted(help)));

            ShellTool shellTool = new ShellTool(
                    hub::post,
                    new ShellTool.Spec(
                            shell.path(),
                            shell.commandKey(),
                            task.forbidden(),
                            task.transientCodes(),
                            task.causedCodes(),
                            REPEAT_THRESHOLD,
                            MAX_REPLY_CHARS,
                            new Guard.Learning(
                                    task.ignore().file(), task.ignore().pathKey(), task.ignore().contentKey())),
                    RetryPolicy.defaults(),
                    Sleeper.real());

            SubmitTool submit = new SubmitTool(
                    shellTool::replies,
                    Pattern.compile(task.codePattern()),
                    (label, code) -> resilient.call(label, Map.of(task.answerKey(), code)));

            Toolbox tools = new Toolbox(List.of(
                    shellTool.tool(task.command().name(), task.command().description()),
                    submit.tool(task.submit().name(), task.submit().description())));

            System.out.println("Model: " + llm.model());

            // Ends on the model's own turn, or as soon as the hub has accepted a submission. Asked of
            // an assistant turn before its calls are dispatched, so a submission written after the
            // hub already accepted one is never made. Leaving its calls unanswered is safe here:
            // nothing seeds another run from this conversation.
            Pattern flagPattern = Pattern.compile(task.flagPattern());
            new Agent(recorded, tools, MAX_ITERATIONS).run(seed,
                    turn -> !turn.hasToolCalls() || findFlag(submit.responses(), flagPattern) != null);

            String flag = findFlag(submit.responses(), flagPattern);
            if (flag == null) {
                throw new IllegalStateException(
                        "No reply matched " + task.flagPattern() + " across " + submit.responses().size()
                                + " submission(s) and " + shellTool.replies().size()
                                + " shell request(s). The run ended without the hub accepting a code.");
            }

            System.out.println(flag);
            transcript.outcome("Earned `" + flag + "` across " + submit.responses().size()
                    + " submission(s) and " + shellTool.replies().size() + " shell request(s).");
            System.out.println("Transcript: " + transcript.file());
        }
    }

    /**
     * The result is taken from what the hub returned, never from what the model says about it: a run
     * that never saw the result in a reply has not earned it.
     *
     * @return the result as the hub wrote it, or null where no reply has carried one yet
     */
    private static String findFlag(List<String> responses, Pattern flag) {
        for (String response : responses) {
            Matcher matcher = flag.matcher(response);
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
    }

    /**
     * Checked by rendering it with a marker, never with the real value. {@code String.formatted}
     * discards an argument it has nowhere to put, so a template that lost its slot renders cleanly
     * and the model is never handed the shell's own description of itself. A marker cannot occur by
     * accident in the output, where the real reply might.
     */
    private static String requireHelpSlot(String template) {
        String marker = "<<help>>";
        String probe;
        try {
            probe = template.formatted(marker);
        } catch (IllegalFormatException e) {
            throw new IllegalStateException(
                    "user.md is not a valid format string: " + e.getMessage()
                            + ". Write a literal percent sign as %%.", e);
        }
        if (!probe.contains(marker)) {
            throw new IllegalStateException(
                    "user.md must contain one %s, where the shell's description of itself goes."
                            + " Rendering it dropped the slot, which no reader of the prompt would see.");
        }
        return template;
    }
}
