package io.github.dbonkowska.dscribe.labs.hub;

import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The piece with no safe failure mode. Every way it goes wrong is quiet and expensive: giving up
 * one attempt early wastes everything the run already spent, retrying too eagerly earns the long
 * block the exercise warns about, and waiting after the last call instead of before the next one
 * looks correct while doing nothing at all.
 *
 * <p>Driven by a fake sender and a recording sleeper, so the schedule is asserted as a list of
 * durations. Nothing here waits — a test that endured its own backoff would take a minute to say
 * what a list comparison says instantly.
 *
 * <p>Fixtures are invented; no lesson supplies them.
 */
class ResilientHubTest {

    private static final String TASK = "x-task";

    /** 1s, 2s, 4s, then a 5s ceiling — four attempts in total. */
    private static final RetryPolicy POLICY =
            new RetryPolicy(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5));

    private static final RateLimitHeaders LIMITS = new RateLimitHeaders(
            List.of("X-Reset"), List.of("X-Remaining"), Duration.ofSeconds(60));

    private final List<Duration> slept = new ArrayList<>();

    @Test
    void retriesAServerErrorAndReturnsWhatFollows(@TempDir Path root) {
        Sender sender = new Sender(failed(503), ok("{\"done\":true}"));

        String body = hub(sender, root).call("probe", Map.of("q", "x"));

        assertEquals("{\"done\":true}", body);
        assertEquals(2, sender.labels.size(), "one retry, so two attempts");
        // the first attempt goes out immediately, so the first wait taken is the one before #2
        assertEquals(List.of(Duration.ofSeconds(2)), slept);
        assertTrue(sender.labels.get(1).contains("2"), () -> sender.labels.toString());
    }

    /**
     * A server that has just refused for asking too often knows when to come back better than any
     * local curve does. Ignoring it is how a run earns a long block.
     */
    @Test
    void prefersTheServersOwnResetOverItsSchedule(@TempDir Path root) {
        Sender sender = new Sender(refused(Map.of("X-Reset", "30")), ok("{}"));

        hub(sender, root).call("probe", Map.of("q", "x"));

        assertEquals(List.of(Duration.ofSeconds(30)), slept);
    }

    @Test
    void givesUpOnceTheAttemptCapIsSpent(@TempDir Path root) {
        Sender sender = new Sender(failed(503), failed(503), failed(503), failed(503), failed(503));

        assertThrows(RuntimeException.class, () -> hub(sender, root).call("probe", Map.of("q", "x")));

        assertEquals(POLICY.maxAttempts(), sender.labels.size(), "the cap bounds the attempts");
    }

    /**
     * The ordering that makes the whole thing worth having. Waiting after the last call would sit
     * out a reset nobody is waiting on; the budget has to be repaid before the *next* request.
     */
    @Test
    void waitsOutASpentBudgetBeforeTheNextCallRatherThanAfterTheLast(@TempDir Path root) {
        Sender sender = new Sender(
                ok("{\"first\":true}", Map.of("X-Reset", "30", "X-Remaining", "0")),
                ok("{\"second\":true}"));
        ResilientHub hub = hub(sender, root);

        hub.call("probe", Map.of("q", "x"));
        assertEquals(List.of(), slept, "nothing waits on a reset after the last call");

        hub.call("probe", Map.of("q", "y"));
        assertEquals(List.of(Duration.ofSeconds(30)), slept, "the debt is paid before the next one");
    }

    @Test
    void doesNotSleepWhenNothingReportsALimit(@TempDir Path root) {
        Sender sender = new Sender(ok("{\"first\":true}"), ok("{\"second\":true}"));
        ResilientHub hub = hub(sender, root);

        hub.call("probe", Map.of("q", "x"));
        hub.call("probe", Map.of("q", "y"));

        assertEquals(List.of(), slept, "an API that announces no budget is not waited on");
    }

    @Test
    void recordsEveryWaitInTheTranscript(@TempDir Path root) throws IOException {
        Sender sender = new Sender(failed(503), ok("{}"));
        RunTranscript transcript = transcript(root);

        hub(sender, transcript).call("probe", Map.of("q", "x"));

        String written = Files.readString(transcript.file(), StandardCharsets.UTF_8);
        assertTrue(written.contains("2"), () -> written);
        assertTrue(written.toLowerCase().contains("wait"), "a run that sat idle has to say why");
    }

    // --- fixtures -----------------------------------------------------------------------------

    private ResilientHub hub(Sender sender, Path root) {
        return hub(sender, transcript(root));
    }

    private ResilientHub hub(Sender sender, RunTranscript transcript) {
        return new ResilientHub(sender, TASK, POLICY, LIMITS, slept::add, transcript);
    }

    private static RunTranscript transcript(Path root) {
        return RunTranscript.open(root.resolve("logs"), "x01", Map.of(), List.of());
    }

    private static HubResponse ok(String body) {
        return ok(body, Map.of());
    }

    private static HubResponse ok(String body, Map<String, String> headers) {
        return new HubResponse(200, headers(headers), body);
    }

    private static HubResponse failed(int status) {
        return new HubResponse(status, headers(Map.of()), "{\"code\":-1}");
    }

    private static HubResponse refused(Map<String, String> headers) {
        return new HubResponse(429, headers(headers), "{\"code\":-429}");
    }

    private static HttpHeaders headers(Map<String, String> values) {
        return HttpHeaders.of(
                values.entrySet().stream().collect(
                        Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue()))),
                (name, value) -> true);
    }

    /** Answers from a queue and keeps the label of every attempt it was asked for. */
    private static final class Sender implements HubSend {

        private final Deque<HubResponse> queued = new ArrayDeque<>();
        private final List<String> labels = new ArrayList<>();

        private Sender(HubResponse... responses) {
            queued.addAll(List.of(responses));
        }

        @Override
        public HubResponse send(String label, String taskName, Object answer) {
            labels.add(label);
            if (queued.isEmpty()) {
                throw new IllegalStateException("asked for more attempts than the test queued");
            }
            return queued.removeFirst();
        }
    }
}
