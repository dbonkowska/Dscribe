package io.github.dbonkowska.dscribe.labs.s04e02;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tested here although per-lesson code is usually left to the hub: when collection goes wrong, the
 * hub's only answer is that the window timed out, which names nothing. A result routed to the wrong
 * job, a wait that never ends or one that ends early all read the same from the outside.
 */
class CollectorTest {

    private static final int PENDING = 7;
    private static final Duration INTERVAL = Duration.ofMillis(300);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** Keys a result by the job that produced it, as the runner does for the data jobs. */
    private static final Function<JsonNode, Optional<String>> BY_SOURCE =
            node -> Optional.of(node.path("origin").asString("")).filter(s -> !s.isEmpty());

    private final List<Duration> waits = new ArrayList<>();

    @Test
    void routesResultsByWhatTheyCarryNotByArrivalOrder() {
        Deque<HubResponse> replies = script(
                ok("{\"state\":7}"),
                ok("{\"state\":1,\"origin\":\"b\",\"n\":2}"),
                ok("{\"state\":7}"),
                ok("{\"state\":1,\"origin\":\"a\",\"n\":1}"));

        Collected<String> collected = collector(replies::removeFirst)
                .collect(Set.of("a", "b"), BY_SOURCE, NOW.plusSeconds(30));

        assertEquals(1, collected.results().get("a").path("n").asInt());
        assertEquals(2, collected.results().get("b").path("n").asInt());
        assertEquals(Set.of("a", "b"), collected.results().keySet());
        // one wait per empty reply, and none once the last result is in
        assertEquals(List.of(INTERVAL, INTERVAL), waits);
        assertEquals(0, replies.size());
    }

    @Test
    void expectingNothingPollsNothing() {
        Collected<String> collected = collector(() -> fail("polled with nothing to wait for"))
                .collect(Set.of(), BY_SOURCE, NOW.plusSeconds(30));

        assertEquals(Map.of(), collected.results());
        assertEquals(List.of(), waits);
    }

    /**
     * The window closes whether or not the queue has delivered, and a loop without a deadline would
     * poll into refusals until someone pressed Ctrl-C. The failure names what never came, because
     * that is the job to look at in the transcript.
     */
    @Test
    void stopsAtTheDeadlineNamingWhatNeverArrived() {
        Instant[] now = {NOW};
        List<Duration> slept = new ArrayList<>();
        Deque<HubResponse> replies = script(ok("{\"state\":1,\"origin\":\"b\"}"));
        Collector collector = new Collector(
                () -> replies.isEmpty() ? ok("{\"state\":7}") : replies.removeFirst(),
                "state", PENDING, Duration.ofSeconds(1), () -> now[0],
                d -> { slept.add(d); now[0] = now[0].plus(d); });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> collector.collect(Set.of("a", "b"), BY_SOURCE, NOW.plusMillis(2500)));

        assertTrue(thrown.getMessage().contains("[a]"), thrown::getMessage);
        assertFalse(thrown.getMessage().contains("b"), thrown::getMessage);
        assertFalse(thrown.getMessage().contains("set aside"),
                () -> "nothing was set aside, so nothing should be said about it: " + thrown.getMessage());
        // never sleeps past the deadline: the last wait is only what was left
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(500)), slept);
    }

    /**
     * A result that matched no key is usually the one the run was waiting for, keyed wrongly. The
     * deadline message says so, so the reader looks at those before at the queue.
     */
    @Test
    void saysAtTheDeadlineHowManyResultsWereSetAside() {
        Instant[] now = {NOW};
        Deque<HubResponse> replies = script(
                ok("{\"state\":1,\"origin\":\"z\"}"),
                ok("{\"state\":1,\"origin\":\"y\"}"));
        Collector collector = new Collector(
                () -> replies.isEmpty() ? ok("{\"state\":7}") : replies.removeFirst(),
                "state", PENDING, Duration.ofSeconds(1), () -> now[0],
                d -> now[0] = now[0].plus(d));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> collector.collect(Set.of("a"), BY_SOURCE, NOW.plusMillis(2500)));

        assertTrue(thrown.getMessage().contains("2 results set aside"), thrown::getMessage);
    }

    /** A refusal is an answer, not a pending job: waiting on after it only spends the window. */
    @Test
    void failsAtOnceOnARefusal() {
        Deque<HubResponse> replies = script(
                new HubResponse(400, TestHeaders.of(Map.of()), "{\"state\":-1,\"message\":\"closed\"}"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> collector(replies::removeFirst).collect(Set.of("a"), BY_SOURCE, NOW.plusSeconds(30)));

        assertTrue(thrown.getMessage().contains("\"state\":-1"), thrown::getMessage);
        assertEquals(List.of(), waits);
    }

    /**
     * Each result can be collected once, so a second one for the same job means the key read from
     * results is not telling jobs apart. Keeping either would hide that.
     */
    @Test
    void failsOnASecondResultForTheSameJob() {
        Deque<HubResponse> replies = script(
                ok("{\"state\":1,\"origin\":\"a\",\"n\":1}"),
                ok("{\"state\":1,\"origin\":\"a\",\"n\":2}"),
                ok("{\"state\":1,\"origin\":\"b\"}"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> collector(replies::removeFirst).collect(Set.of("a", "b"), BY_SOURCE, NOW.plusSeconds(30)));

        assertTrue(thrown.getMessage().contains("a"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("\"n\":1"),
                () -> "the first result has to survive into the message: " + thrown.getMessage());
    }

    /** A result nobody waits for is kept for the transcript, not dropped and not fatal. */
    @Test
    void setsAsideAResultNobodyExpects() {
        Deque<HubResponse> replies = script(
                ok("{\"state\":1,\"origin\":\"z\"}"),
                ok("{\"state\":1,\"message\":\"no source at all\"}"),
                ok("{\"state\":1,\"origin\":\"a\"}"),
                ok("{\"state\":1,\"origin\":\"b\"}"));

        Collected<String> collected = collector(replies::removeFirst)
                .collect(Set.of("a", "b"), BY_SOURCE, NOW.plusSeconds(30));

        assertEquals(Set.of("a", "b"), collected.results().keySet());
        assertEquals(2, collected.unexpected().size());
        assertEquals("z", collected.unexpected().get(0).path("origin").asString());
    }

    private Collector collector(Supplier<HubResponse> poll) {
        return new Collector(poll, "state", PENDING, INTERVAL, InstantSource.fixed(NOW), waits::add);
    }

    private static Deque<HubResponse> script(HubResponse... replies) {
        return new ArrayDeque<>(List.of(replies));
    }

    private static HubResponse ok(String body) {
        return new HubResponse(200, TestHeaders.of(Map.of()), body);
    }
}
