package io.github.dbonkowska.dscribe.labs.s05e02;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.Artifacts;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubClient;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import io.github.dbonkowska.dscribe.labs.lesson.Lesson;
import io.github.dbonkowska.dscribe.labs.s05e02.Dialogue.Burnt;
import io.github.dbonkowska.dscribe.labs.s05e02.Dialogue.Say;
import io.github.dbonkowska.dscribe.labs.s05e02.Dialogue.Step;
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.llm.ResponseFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A spoken, staged call: each turn goes out as synthesised audio, each reply comes back as audio and
 * is transcribed, and code — not the model — decides what is said next.
 *
 * <p>Turn order is what the other side grades, and a wrong turn ends the session, so the order is
 * held by {@link Dialogue}. The model's only job is {@link Classification}: what was this reply?
 * A reply nothing fits ends the session rather than being guessed at, and a burnt session is
 * started again from the beginning, up to {@link #MAX_SESSIONS}.
 *
 * <p>Every fixed line is synthesised once, before the first session opens, and reused across
 * restarts. Only the line naming what was chosen is synthesised while a session is open. Every
 * clip is transcribed back before it is used and synthesised again if a code it names was not
 * heard ({@link Clarity}): synthesis varies from run to run, and a misheard code burns a session.
 *
 * <p>The hub's {@code code} and {@code message} are recorded on every step and never compared: they
 * are the hub's account of progress, kept as its words beside the dialogue's own decisions rather
 * than as a second counter. Only a non-200 reply ends a session by itself, and a 5xx or 429 is
 * retried before it counts as one.
 *
 * <p>Nothing here names the task: what is said, what can be chosen and the prompt are in the bundle.
 */
public class S05E02 {

    private static final String LESSON = "s05e02";

    /**
     * One short structured call per reply. A preference, not the last word — {@code -Dopenrouter.model},
     * {@code OPENROUTER_MODEL} and {@code openrouter.model} each still win over it.
     */
    private static final String MODEL = "google/gemini-3.8-flash";

    /**
     * Native Polish voices, at $0.000015 a character. Overridden only by {@code openrouter.voice.model}.
     * The voice itself is in the bundle, because it is valid for this model alone.
     */
    private static final String VOICE_MODEL = "microsoft/mai-voice-2.1-flash";

    /** Overridden only by {@code openrouter.speech.model}. */
    private static final String SPEECH_MODEL = "openai/whisper-large-v3";

    /** Each session repeats every turn's spend, so a run that keeps burning stops here. */
    private static final int MAX_SESSIONS = 3;

    /**
     * Generous for three stages: interrupts add turns without moving the stage, and an operator who
     * keeps interrupting would otherwise hold the session open until the window closes.
     */
    private static final int MAX_TURNS = 12;

    /**
     * How many times a line is synthesised before giving up on it. Each attempt is a few cents of
     * nothing — a TTS call and a Whisper call — against a session it would otherwise burn.
     */
    private static final int MAX_SYNTHESES = 3;

    /**
     * The hub announces no reset header, so this caps a wait nothing will ask for; the schedule is
     * {@link RetryPolicy#defaults()}.
     */
    private static final Duration MAX_WAIT = Duration.ofSeconds(30);

    /**
     * The key {@code start} is sent under, which the constitution names as generic protocol vocabulary.
     * The key a turn is sent under is the exercise's, so it comes from {@link TaskParams#audioKey()}.
     */
    private static final String ACTION = "action";

    /** What the hub's replies carry, read rather than sent, so protocol vocabulary kept here. */
    private static final String AUDIO = "audio";
    private static final String CODE = "code";
    private static final String MESSAGE = "message";
    private static final String HINT = "hint";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);
        LlmClient voice = new LlmClient(labsConfig.llm().voice()).defaultModel(VOICE_MODEL);
        LlmClient speech = new LlmClient(labsConfig.llm().speech()).defaultModel(SPEECH_MODEL);

        // Everything refusable is refused here, before a line is synthesised or a session opened.
        Lesson lesson = Lesson.of(labsConfig, LESSON);
        TaskParams task = lesson.task(TaskParams.class);
        Pattern flagPattern = Pattern.compile(task.flagPattern());
        String system = lesson.prompt("system.md");
        if (system.isBlank()) {
            throw new IllegalStateException("system.md in the s05e02 lesson bundle is blank. Write it before running.");
        }
        ResponseFormat classification = ResponseFormat.jsonSchema("classification", Classification.schema(task));

        Path runDir = Artifacts.of(labsConfig.dataDir(), LESSON).file(RUN.format(LocalDateTime.now()));

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("voice model", voice.model());
        settings.put("voice", task.voice());
        settings.put("speech model", speech.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max sessions", String.valueOf(MAX_SESSIONS));
        settings.put("max turns", String.valueOf(MAX_TURNS));
        settings.put("audio", runDir.toString());

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                LESSON,
                settings,
                List.of(labsConfig.hub().apiKey(), labsConfig.llm().apiKey()))) {

            HubClient client = new HubClient(labsConfig.hub(), transcript);
            // a 5xx or 429 is waited out and the same turn sent again, a risk accepted on purpose: a
            // gateway error does not say whether the hub had already applied the turn, and if it had,
            // the retry is a repeated turn — the out-of-order line the dialogue exists to prevent.
            // Two 502s burnt whole sessions in one run, so a retry was judged the smaller risk; a
            // repeat shows up in the record as two attempts of one turn
            ResilientHub hub = new ResilientHub(
                    client::send, task.verifyTask(), RetryPolicy.defaults(),
                    new RateLimitHeaders(List.of(), MAX_WAIT), Sleeper.real(), transcript);
            LlmClient speaking = voice.withTranscript(transcript.delegated("voice"));
            LlmClient listening = speech.withTranscript(transcript.delegated("speech"));
            // delegated rather than the main path: every call is a fresh two-message conversation, and
            // the main path renders only what is new in one growing conversation
            LlmClient classifying = llm.withTranscript(transcript.delegated("classify"));

            Map<String, byte[]> prerecorded = prerecord(task, speaking, listening, transcript, runDir);

            List<String> burnt = new ArrayList<>();
            for (int session = 1; session <= MAX_SESSIONS; session++) {
                Call call = new Call(session, hub, task, flagPattern, transcript, runDir);
                Optional<String> flag = call.run(prerecorded, speaking, listening, classifying, system, classification);
                if (flag.isPresent()) {
                    System.out.println(flag.get());
                    transcript.outcome("Earned `" + flag.get() + "` in session " + session + ".");
                    System.out.println("Transcript: " + transcript.file());
                    return;
                }
                burnt.add("session " + session + ": " + call.burntReason);
            }

            transcript.outcome("No flag in " + MAX_SESSIONS + " sessions:\n\n- " + String.join("\n- ", burnt));
            System.out.println("Transcript: " + transcript.file());
            throw new IllegalStateException("No flag in " + MAX_SESSIONS + " sessions: " + burnt);
        }
    }

    /** Every fixed line, synthesised once and kept on disk beside the run. */
    private static Map<String, byte[]> prerecord(
            TaskParams task, LlmClient speaking, LlmClient listening, RunTranscript transcript, Path runDir) {
        List<String> lines = new ArrayList<>();
        task.stages().stream().map(TaskParams.Stage::say)
                .filter(say -> !say.contains(TaskParams.CHOSEN_SLOT))
                .forEach(lines::add);
        task.interrupts().forEach(interrupt -> lines.add(interrupt.say()));

        Map<String, byte[]> prerecorded = new LinkedHashMap<>();
        for (String line : lines) {
            byte[] clip = clearly(line, task, speaking, listening, transcript).orElseThrow(() ->
                    new IllegalStateException("Could not synthesise \"" + line + "\" so that its codes are heard"
                            + " in " + MAX_SYNTHESES + " attempts. Reword it in the bundle; see the notes."));
            prerecorded.put(line, clip);
            write(runDir.resolve("said").resolve(String.format("%02d.mp3", prerecorded.size())), clip);
        }
        return prerecorded;
    }

    /** One session, from {@code start} until a flag or a reason it cannot go on. */
    private static final class Call {

        private final int session;
        private final ResilientHub hub;
        private final TaskParams task;
        private final Pattern flagPattern;
        private final RunTranscript transcript;
        private final Path runDir;
        private String burntReason;

        Call(int session, ResilientHub hub, TaskParams task, Pattern flagPattern, RunTranscript transcript, Path runDir) {
            this.session = session;
            this.hub = hub;
            this.task = task;
            this.flagPattern = flagPattern;
            this.transcript = transcript;
            this.runDir = runDir;
        }

        Optional<String> run(
                Map<String, byte[]> prerecorded, LlmClient speaking, LlmClient listening, LlmClient classifying,
                String system, ResponseFormat classification) {

            HubResponse started = hub.send(
                    "session " + session + " · " + task.startAction(), Map.of(ACTION, task.startAction()));
            // the hub refusing to open a session is not a session burnt: another attempt would be refused too
            if (started.status() != 200) {
                throw new IllegalStateException("Hub refused to start [" + started.status() + "]: " + started.body());
            }
            JsonNode opened = MAPPER.readTree(started.body());
            transcript.note("session " + session + " · start", hubWords(opened));

            Step step = Dialogue.opening(task);
            for (int turn = 1; turn <= MAX_TURNS; turn++) {
                if (step.next() instanceof Burnt(String reason)) {
                    return burn(reason);
                }
                Say say = (Say) step.next();
                Optional<byte[]> spoken = say.prerecorded()
                        ? Optional.of(prerecorded.get(say.text()))
                        : clearly(say.text(), task, speaking, listening, transcript);
                if (spoken.isEmpty()) {
                    return burn("turn " + turn + ": could not synthesise the line so that its codes are heard");
                }
                byte[] clip = spoken.get();

                HubResponse response = hub.send(
                        "session " + session + " · turn " + turn,
                        Map.of(task.audioKey(), Base64.getEncoder().encodeToString(clip)));

                Matcher found = flagPattern.matcher(response.body());
                if (found.find()) {
                    return Optional.of(found.group());
                }
                if (response.status() != 200) {
                    return burn("turn " + turn + ": hub replied [" + response.status() + "]: " + refusal(response.body()));
                }

                JsonNode reply = MAPPER.readTree(response.body());
                String audio = reply.path(AUDIO).asString("");
                if (audio.isBlank()) {
                    return burn("turn " + turn + ": reply carried no audio (" + hubWords(reply) + ")");
                }
                // strict, after removing only what a transport may add, so a corrupt payload is named as one
                byte[] heardClip = Base64.getDecoder().decode(audio.replaceAll("\\s", ""));
                write(runDir.resolve("replies").resolve(String.format("%d-%03d.mp3", session, turn)), heardClip);
                String heard = listening.transcribe(heardClip, "mp3").text();

                // a label outside the set ends the run, not the session: that is a bug in the schema or
                // the provider, not a reply from the operator, and another session would meet it again
                Classification labelled = Classification.parse(classifying.sendStructured(List.of(
                        new Message(Role.system, system),
                        new Message(Role.user, "We said:\n" + say.text() + "\n\nThe operator replied:\n" + heard)),
                        classification, Map.class), task);

                int answered = step.dialogue().stage();
                step = step.dialogue().step(labelled.label(), labelled.chosen(), heard);
                // named for the stage the reply answered, whose line is quoted below; where the dialogue
                // went from there is the arrow
                transcript.note("session " + session + " · turn " + turn + " · stage " + answered
                                + " → " + step.dialogue().stage(),
                        hubWords(reply)
                                + "\n\nsaid:\n\n> " + say.text()
                                + "\n\nheard:\n\n> " + heard
                                + "\n\nlabel `" + labelled.label() + "` · chosen " + step.dialogue().chosen());
            }
            return burn("turns ran out: " + MAX_TURNS + " without a flag");
        }

        private Optional<String> burn(String reason) {
            burntReason = reason;
            transcript.note("session " + session + " · burnt", reason);
            return Optional.empty();
        }
    }

    /**
     * {@code line} synthesised and transcribed back until every code it names is heard — see
     * {@link Clarity}. Empty after {@link #MAX_SYNTHESES} attempts, each miss recorded.
     */
    private static Optional<byte[]> clearly(
            String line, TaskParams task, LlmClient speaking, LlmClient listening, RunTranscript transcript) {
        for (int attempt = 1; attempt <= MAX_SYNTHESES; attempt++) {
            byte[] clip = speaking.speak(line, task.voice(), null);
            String heard = listening.transcribe(clip, "mp3").text();
            List<String> missing = Clarity.missing(line, heard, task.candidates());
            if (missing.isEmpty()) {
                return Optional.of(clip);
            }
            transcript.note("synthesis " + attempt + " misheard",
                    "said:\n\n> " + line + "\n\nheard:\n\n> " + heard + "\n\nnot heard: " + missing);
        }
        return Optional.empty();
    }

    /**
     * Why the hub refused, without the recording it sent along: the refusal goes into a note, the
     * outcome and an exception message, none of which elide, and the audio would bury the one line
     * worth reading — the {@code hint} naming what was missing. A body that is not JSON (a gateway's
     * {@code error code: 502}) is short, and kept as it came.
     */
    private static String refusal(String body) {
        JsonNode reply;
        try {
            reply = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            return body.strip();
        }
        if (!reply.isObject()) {
            return body.strip();
        }
        String hint = reply.path(HINT).asString("");
        return hubWords(reply) + (hint.isBlank() ? "" : "\n\nhint: " + hint);
    }

    /** The hub's own account of where the call is — recorded, never compared. */
    private static String hubWords(JsonNode reply) {
        return "hub: code `" + reply.path(CODE).asString("") + "` · " + reply.path(MESSAGE).asString("");
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
