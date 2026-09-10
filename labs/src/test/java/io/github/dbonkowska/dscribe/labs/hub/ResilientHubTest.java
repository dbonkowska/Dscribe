package io.github.dbonkowska.dscribe.labs.hub;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The piece with no safe failure mode. Every way it goes wrong is quiet and expensive: giving up
 * one attempt early wastes everything the run already spent, retrying too eagerly earns the long
 * block the exercise warns about, and ignoring the number a refusal gave you earns it twice.
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

    private static final RateLimitHeaders LIMITS =
            new RateLimitHeaders(List.of("X-Reset"), Duration.ofSeconds(60));

    private final List<Duration> slept = new ArrayList<>();

    @Test
    void retriesAServerErrorAndReturnsWhatFollows(@TempDir Path root) {
        Sender sender = new Sender(failed(503), ok("{\"done\":true}"));

        String body = hub(sender, root).call("probe", Map.of("q", "x"));

        assertEquals("{\"done\":true}", body);
        assertEquals(2, sender.labels.size(), "one retry, so two attempts");
        assertEquals(List.of(Duration.ofSeconds(1)), slept);
        assertEquals("probe · attempt 2", sender.labels.get(1));
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
     * The API this was built against attaches rate-limit headers only to refusals, so a run's
     * successful calls go out back to back. Nothing waits on a limit nobody announced.
     */
    @Test
    void doesNotSleepWhenNothingReportsALimit(@TempDir Path root) {
        Sender sender = new Sender(ok("{\"first\":true}"), ok("{\"second\":true}"));
        ResilientHub hub = hub(sender, root);

        hub.call("probe", Map.of("q", "x"));
        hub.call("probe", Map.of("q", "y"));

        assertEquals(List.of(), slept, "an API that announces no budget is not waited on");
    }

    /** A successful response is never waited on, whatever it happens to carry. */
    @Test
    void neverWaitsAfterACallThatSucceeded(@TempDir Path root) {
        Sender sender = new Sender(ok("{\"first\":true}", Map.of("X-Reset", "30")), ok("{}"));
        ResilientHub hub = hub(sender, root);

        hub.call("probe", Map.of("q", "x"));
        hub.call("probe", Map.of("q", "y"));

        assertEquals(List.of(), slept, "the reset belongs to a refusal, not to a success");
    }

    @Test
    void recordsEveryWaitInTheTranscript(@TempDir Path root) throws IOException {
        Sender sender = new Sender(failed(503), ok("{}"));
        RunTranscript transcript = transcript(root);

        hub(sender, transcript).call("probe", Map.of("q", "x"));

        String written = Files.readString(transcript.file(), StandardCharsets.UTF_8);
        assertTrue(written.contains("waited 1s"), () -> written);
        assertTrue(written.contains("status 503 on attempt 1"), () -> written);
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
        return new HubResponse(200, TestHeaders.of(headers), body);
    }

    private static HubResponse failed(int status) {
        return new HubResponse(status, TestHeaders.of(Map.of()), "{\"code\":-1}");
    }

    private static HubResponse refused(Map<String, String> headers) {
        return new HubResponse(429, TestHeaders.of(headers), "{\"code\":-429}");
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
