package io.github.dbonkowska.dscribe.labs.s05e04;

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
import io.github.dbonkowska.dscribe.llm.LlmClient;
import io.github.dbonkowska.dscribe.llm.ResponseFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A control loop with no chat window: code runs every step of every game, and the model is called
 * only to label one hint.
 *
 * <p>Each game is {@link Game}, and a lost or abandoned one starts again, up to {@link #MAX_GAMES}.
 * Only a retry budget spent on an endpoint ends the run early, because another game would meet the
 * same outage. A label outside {@link Bearing} ends it too: that is a bug in the schema or the
 * provider, not a reply to play on from.
 *
 * <p>Nothing here names the task: the endpoints, commands, reply fields and prompt are in the bundle.
 */
public class S05E04 {

    private static final Logger log = LoggerFactory.getLogger(S05E04.class);

    private static final String LESSON = "s05e04";
    private static final String MODEL = "google/gemini-3.8-flash";

    /** Raised from 5 by the probe: most games were lost, and a game costs a few cheap calls. */
    private static final int MAX_GAMES = 10;

    /**
     * Eight attempts, because the probe saw 5 straight 502s on one endpoint and a spent budget ends
     * the run. Flattening at 8 seconds, since a gateway error carries no reason to wait for long.
     */
    private static final RetryPolicy RETRY = new RetryPolicy(8, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(8));

    private static final Duration MAX_WAIT = Duration.ofSeconds(30);

    public static void main(String[] args) {
        LabsConfig labsConfig = LabsConfig.load();
        LlmClient llm = new LlmClient(labsConfig.llm()).defaultModel(MODEL);

        // Everything refusable is refused here, before a game is opened.
        Lesson lesson = Lesson.of(labsConfig, LESSON);
        TaskParams task = lesson.task(TaskParams.class);
        String system = lesson.requiredPrompt("system.md");
        ResponseFormat format = ResponseFormat.jsonSchema("bearing", Bearing.schema());

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("model", llm.model());
        settings.put("llm base url", labsConfig.llm().baseUrl());
        settings.put("hub base url", labsConfig.hub().baseUrl());
        settings.put("max games", String.valueOf(MAX_GAMES));

        try (RunTranscript transcript = RunTranscript.open(
                labsConfig.dataDir().resolve("logs"),
                LESSON,
                settings,
                List.of(labsConfig.hub().apiKey(), labsConfig.llm().apiKey()))) {

            HubClient client = new HubClient(labsConfig.hub(), transcript);
            // an empty header list, because this hub sends no reset header on a refusal
            ResilientHub hub = new ResilientHub(
                    client::send, task.verifyTask(), RETRY,
                    new RateLimitHeaders(List.of(), MAX_WAIT), Sleeper.real(), transcript);
            // delegated rather than the main path: every call is a fresh two-message conversation, and
            // the main path renders only what is new in one growing conversation
            LlmClient classifying = llm.withTranscript(transcript.delegated("classify"));

            List<String> abandoned = new ArrayList<>();
            for (int game = 1; game <= MAX_GAMES; game++) {
                HubField field = new HubField(game, hub, client, classifying, system, format, task, transcript);
                switch (new Game(field, task).play()) {
                    case Game.Outcome.Flagged flagged -> {
                        System.out.println(flagged.flag());
                        transcript.outcome("Earned `" + flagged.flag() + "` in game " + game + ".");
                        System.out.println("Transcript: " + transcript.file());
                        return;
                    }
                    case Game.Outcome.Abandoned stopped -> {
                        log.info("game {} abandoned: {}", game, stopped.reason());
                        transcript.note("game " + game + " abandoned", stopped.reason());
                        abandoned.add("game " + game + ": " + stopped.reason());
                    }
                }
            }

            transcript.outcome("No flag in " + MAX_GAMES + " games:\n\n- " + String.join("\n- ", abandoned));
            System.out.println("Transcript: " + transcript.file());
            throw new IllegalStateException("No flag in " + MAX_GAMES + " games: " + abandoned);
        }
    }

    /** One game's view of the outside world: every endpoint behind one retry policy. */
    private record HubField(
            int game,
            ResilientHub hub,
            HubClient client,
            LlmClient classifying,
            String system,
            ResponseFormat format,
            TaskParams task,
            RunTranscript transcript) implements Game.Field {

        @Override
        public HubResponse start() {
            return hub.send(named("start"), Map.of(task.commandKey(), task.start()));
        }

        @Override
        public HubResponse scan() {
            return hub.retrying(named("scan"), attempt -> client.read(attempt, task.statusPath()));
        }

        /** Safe to retry: a repeat after the first took effect is refused, and harmlessly. */
        @Override
        public void disarm(Reading.Targeted refusal) {
            Map<String, Object> body = Map.of(
                    task.frequencyKey(), refusal.frequency(),
                    task.hashKey(), refusal.digest(task.digestAlgorithm(), task.digestSuffix()));
            hub.retrying(named("disarm"), attempt -> client.exchange(attempt, task.statusPath(), body));
        }

        @Override
        public HubResponse hint() {
            return hub.retrying(named("hint"), attempt -> client.exchange(attempt, task.hintPath(), Map.of()));
        }

        @Override
        public Bearing label(String hintText) {
            Bearing bearing = Bearing.parse(classifying.sendStructured(List.of(
                    new Message(Role.system, system),
                    new Message(Role.user, hintText)), format, Map.class));
            transcript.note(named("hint labelled"), "> " + hintText + "\n\nlabel `" + bearing.label() + "`");
            return bearing;
        }

        /**
         * Retried on a 5xx or 429 like every other call, a risk accepted on purpose: a gateway error
         * does not say whether the hub had already applied the move, and if it had, the retry steps
         * twice, past a column nothing checked. A dead run is the alternative, the probe saw no 5xx
         * on this endpoint, and {@link Game} notes any column it did not expect.
         */
        @Override
        public HubResponse move(String command) {
            return hub.send(named("move " + command), Map.of(task.commandKey(), command));
        }

        @Override
        public void note(String note) {
            log.warn("game {}: {}", game, note);
            transcript.note(named("unexpected column"), note);
        }

        private String named(String call) {
            return "game " + game + " · " + call;
        }
    }
}
