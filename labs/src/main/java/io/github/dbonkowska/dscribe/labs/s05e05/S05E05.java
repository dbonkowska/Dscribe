package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.labs.s05e05.NextSteps.Instruction;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.llm.ResponseFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An assistant for a device that is driven partly over the API and partly by a person at a web page.
 *
 * <p>Code holds the route: a {@link Watcher} polls the device and nothing else reads it, an
 * {@link Assistant} sets what the API allows and tells the operator the rest, and the operator's work
 * is checked against the device's own state rather than their word. The model is called only to take a
 * free-text hint apart, and code does the arithmetic.
 *
 * <p>The page shows the flag from its own call, and once the tunnel is open a {@code getConfig} reply
 * carries it too (seen in the second real run), so the watcher's flag match catches it. The run ends
 * on reaching the meeting date, on a stop, on going idle, or on Ctrl-C, and the transcript's outcome
 * says which. After the final cue it keeps cueing: a missed phase comes round again.
 *
 * <p>Nothing here names the task: actions, sent names, weights, dates and texts are in the bundle.
 */
public class S05E05 {

    private static final Logger log = LoggerFactory.getLogger(S05E05.class);

    private static final String LESSON = "s05e05";
    private static final String MODEL = "google/gemini-3.8-flash";

    /** The key every call names its action under. Protocol vocabulary, the same for any action. */
    private static final String ACTION_KEY = "action";

    /** The probe polled once a second for 15 seconds and was never refused. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    /** Long enough for a person to find a control on the page; the phase rotating does not count. */
    private static final Duration IDLE_LIMIT = Duration.ofMinutes(15);

    /** Both actions are safe to repeat: one reads, and the other sets a whole value. */
    private static final RetryPolicy RETRY = new RetryPolicy(8, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(8));

    /** Required by {@link RateLimitHeaders}, and never reached: this hub sends no header to cap. */
    private static final Duration MAX_WAIT = Duration.ofSeconds(30);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        // Everything refusable is refused here, before the device is read.
        Lesson lesson = Lesson.of(labsConfig, LESSON);
        TaskParams task = lesson.task(TaskParams.class);
        String system = lesson.requiredPrompt("system.md");
        LevelTable levels = LevelTable.parse(lesson.requiredPrompt(task.docsFile()));
        Clock clock = Clock.system(task.zone());
        LocalDate today = LocalDate.now(clock);
        levels.require(task.fetchDate().getYear());
        levels.require(task.meetingDate().getYear());
        levels.require(today.getYear());
        ResponseFormat format = ResponseFormat.jsonSchema("hint_reading", HintReading.schema());
        Pattern flag = Pattern.compile(task.flagPattern());

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("today", today + " (" + task.zone() + ")");
        settings.put("poll interval", POLL_INTERVAL.toString());
        settings.put("idle limit", IDLE_LIMIT.toString());

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                LESSON,
                settings,
                List.of(labsConfig.hub().apiKey(), labsConfig.llm().apiKey()))) {

            HubClient client = new HubClient(labsConfig.hub(), transcript);
            ResilientHub hub = new ResilientHub(
                    client::send, task.verifyTask(), RETRY,
                    new RateLimitHeaders(List.of(), MAX_WAIT), Sleeper.real(), transcript);
            // delegated: every reading is a fresh two-message conversation
            LlmClient reading = llm.withTranscript(transcript.delegated("hint"));

            Console console = new Console(task, transcript);
            Assistant assistant = new Assistant(
                    new HubDevice(hub, reading, system, format, task, transcript),
                    console,
                    new Stage.Route(task.fetchDate(), task.meetingDate(), task.tunnelBattery()),
                    levels,
                    new SyncRatio(task.sync()),
                    new GoGate(task.phaseBounds()),
                    task.stabilizationMin(),
                    task.stabilizationMax(),
                    () -> LocalDate.now(clock));
            Run run = new Run(assistant, console, transcript);

            // Ctrl-C is an expected way to end this run, and it does not unwind main, so the outcome
            // is written here. Exactly one of this hook and the try-with-resources closes the
            // transcript: a normal end removes the hook first.
            Thread onShutdown = new Thread(() -> {
                transcript.outcome("Ended by Ctrl-C. " + run.summary());
                transcript.close();
            });
            Runtime.getRuntime().addShutdownHook(onShutdown);

            System.out.println("Model: " + llm.model());
            System.out.println("Transcript: " + transcript.file());

            Watcher watcher = new Watcher(
                    () -> hub.send("getConfig", Map.of(ACTION_KEY, task.getConfigAction())),
                    Sleeper.real(), clock, POLL_INTERVAL, IDLE_LIMIT, flag);
            Watcher.Outcome watched;
            try {
                watched = watcher.run(run);
            } catch (RuntimeException e) {
                Runtime.getRuntime().removeShutdownHook(onShutdown);
                throw e;
            }
            Runtime.getRuntime().removeShutdownHook(onShutdown);

            String outcome = run.outcome(watched);
            System.out.println(outcome);
            transcript.outcome(outcome);
            System.out.println("Transcript: " + transcript.file());
            if (!run.endedWell()) {
                throw new IllegalStateException(outcome);
            }
        }
    }

    /**
     * The listener the watcher drives: the assistant, plus a flag printed and kept wherever one shows.
     *
     * <p>It does not end on the final cue, or on any change after it. The first real run did, and
     * ended on the flux drop of the next phase rotation, three seconds after the cue, taking away the
     * next window to click in. Arriving at the meeting date ends it; Ctrl-C ends it otherwise.
     */
    private static final class Run implements Watcher.Listener {
        private final Assistant assistant;
        private final Console console;
        private final RunTranscript transcript;
        private final List<String> flags = new ArrayList<>();

        Run(Assistant assistant, Console console, RunTranscript transcript) {
            this.assistant = assistant;
            this.console = console;
            this.transcript = transcript;
        }

        @Override
        public Watcher.Verdict on(Watcher.Event event) {
            if (event instanceof Watcher.Event.Flagged flagged) {
                System.out.println(flagged.match());
                transcript.note("flag", "`" + flagged.match() + "`");
                flags.add(flagged.match());
                return Watcher.Verdict.WATCH;
            }
            return assistant.on(event);
        }

        /** Arriving is the one way the watch itself ends as intended; the other is Ctrl-C. */
        boolean endedWell() {
            return assistant.result().map(result -> result instanceof Assistant.Result.Done).orElse(false);
        }

        String outcome(Watcher.Outcome watched) {
            String how = switch (assistant.result().orElse(null)) {
                case Assistant.Result.Done done -> "The device is at the meeting date.";
                case Assistant.Result.Stop stop -> "Stopped: " + stop.reason();
                case null -> switch (watched) {
                    case Watcher.Outcome.Idle idle -> "Idle: nothing but the phase and flux changed for "
                            + IDLE_LIMIT.toMinutes() + " minutes. Last state: " + idle.last();
                    case Watcher.Outcome.Unreadable unreadable -> "Unreadable reply: " + unreadable.reason();
                    case Watcher.Outcome.Stopped stopped -> "The watch stopped. Last state: " + stopped.last();
                };
            };
            return how + " " + summary();
        }

        /** What the run has seen of the result. The page shows it too, so its absence here is no failure. */
        String summary() {
            if (!flags.isEmpty()) {
                return "Flag seen in a reply: " + String.join(", ", flags) + ".";
            }
            return console.finalCueGiven()
                    ? "The final cue was given, but no reply seen here carried the flag. Check the browser."
                    : "The final cue was never given.";
        }
    }

    /** The operator's console. The assistant calls it only when what it says changes. */
    private static final class Console implements Assistant.Operator {
        private final TaskParams task;
        private final RunTranscript transcript;
        private volatile boolean finalCue;

        Console(TaskParams task, RunTranscript transcript) {
            this.task = task;
            this.transcript = transcript;
        }

        boolean finalCueGiven() {
            return finalCue;
        }

        @Override
        public void say(List<Instruction> instructions) {
            print("instructions", instructions.stream().map(this::render).map(line -> "- " + line)
                    .collect(Collectors.joining("\n")));
        }

        @Override
        public void cue(Assistant.Cue cue) {
            String text = switch (cue) {
                case Assistant.Cue.Go go when go.last() -> {
                    finalCue = true;
                    yield task.say().go() + "\n" + task.say().browser();
                }
                case Assistant.Cue.Go go -> task.say().go();
                case Assistant.Cue.Wait wait -> task.say().waitPhase().formatted(wait.wantPhase(), wait.nowPhase());
            };
            print("cue", text);
        }

        @Override
        public void note(String note) {
            log.info(note);
            transcript.note("note", note);
        }

        private String render(Instruction instruction) {
            TaskParams.Say say = task.say();
            return switch (instruction) {
                case Instruction.Standby standby -> say.standby();
                case Instruction.Active active -> say.active();
                case Instruction.Switch flip -> (flip.on() ? say.switchOn() : say.switchOff())
                        .formatted(flip.side() == NextSteps.Side.PAST ? task.labels().past() : task.labels().future());
                case Instruction.Level level -> say.level().formatted(level.level());
            };
        }

        private void print(String heading, String text) {
            System.out.println();
            System.out.println(text);
            transcript.note("operator · " + heading, text);
        }
    }

    /** The device as the assistant sees it: every call behind one retry policy. */
    private record HubDevice(
            ResilientHub hub,
            LlmClient reading,
            String system,
            ResponseFormat format,
            TaskParams task,
            RunTranscript transcript) implements Assistant.Device {

        @Override
        public HubResponse configure(NextSteps.Param param, Number value) {
            String name = switch (param) {
                case DAY -> task.params().day();
                case MONTH -> task.params().month();
                case YEAR -> task.params().year();
                case SYNC_RATIO -> task.params().syncRatio();
                case STABILIZATION -> task.params().stabilization();
            };
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put(ACTION_KEY, task.configureAction());
            answer.put(task.paramKey(), name);
            answer.put(task.valueKey(), value);
            return hub.send("configure " + name + "=" + value, answer);
        }

        /**
         * A reply the model could not give in shape is an unclear reading: a failed attempt, not a
         * crash. That covers a reply that is not JSON, which the client throws on while reading it,
         * as well as one that is JSON but not the schema.
         */
        @Override
        public HintReading label(String hint) {
            HintReading parsed;
            try {
                parsed = HintReading.parse(reading.sendStructured(List.of(
                        new Message(Role.system, system),
                        new Message(Role.user, hint)), format, Map.class));
            } catch (IllegalStateException | JacksonException e) {
                transcript.note("hint refused", "> " + hint + "\n\n" + e.getMessage());
                return new HintReading(0, 0, HintReading.Direction.UNCLEAR);
            }
            transcript.note("hint read", "> " + hint + "\n\nbase `" + parsed.base() + "`, delta `" + parsed.delta()
                    + "`, direction `" + parsed.direction().label() + "`");
            return parsed;
        }
    }
}
