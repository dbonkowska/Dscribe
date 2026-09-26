package io.github.dbonkowska.dscribe.labs.s04e02;

import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.Sleeper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Drains a server-side queue that hands back one finished job per call, in whatever order the jobs
 * finished.
 *
 * <p>The jobs were queued back to back, and the server runs them in parallel; this side needs no
 * threads, only a loop that asks again until every job it is waiting for has come back. What each
 * result belongs to is read from the result itself — arrival order means nothing here.
 *
 * <p>The clock and the waiting are injected, so a test asserts what the loop decided to wait
 * rather than enduring it.
 */
public class Collector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Supplier<HubResponse> poll;
    private final String codeField;
    private final int pendingCode;
    private final Duration interval;
    private final InstantSource clock;
    private final Sleeper sleeper;

    /**
     * @param poll        one collect call against the hub
     * @param codeField   the field of a reply that carries its status code
     * @param pendingCode the code that means "nothing is ready yet"
     * @param interval    the wait after such a reply
     */
    public Collector(
            Supplier<HubResponse> poll, String codeField, int pendingCode, Duration interval, InstantSource clock,
            Sleeper sleeper) {
        this.poll = poll;
        this.codeField = codeField;
        this.pendingCode = pendingCode;
        this.interval = interval;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Polls until every key in {@code expected} has a result. Fails at the deadline, on any reply
     * that is not a 200, and on a second result for a key already filled; a result for no expected
     * key is set aside in {@link Collected#unexpected()}.
     *
     * @param keyOf    reads which job a result belongs to, from the result itself
     * @param deadline when the run stops waiting
     */
    public <K> Collected<K> collect(Set<K> expected, Function<JsonNode, Optional<K>> keyOf, Instant deadline) {
        Map<K, JsonNode> results = new LinkedHashMap<>();
        List<JsonNode> unexpected = new ArrayList<>();
        while (!results.keySet().containsAll(expected)) {
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw new IllegalStateException(
                        "Deadline passed still waiting for " + missing(expected, results) + ".");
            }

            HubResponse response = poll.get();
            // A refusal is an answer, not a pending job: the window has closed or the call was wrong,
            // and waiting on would only spend what is left of it.
            if (response.status() != 200) {
                throw new IllegalStateException(
                        "Collect refused [" + response.status() + "]: " + response.body());
            }

            JsonNode reply = MAPPER.readTree(response.body());
            if (reply.path(codeField).asInt() == pendingCode) {
                // never past the deadline: a full interval could end after the window has closed
                sleeper.await(interval.compareTo(remaining) < 0 ? interval : remaining);
                continue;
            }

            Optional<K> key = keyOf.apply(reply).filter(expected::contains);
            if (key.isEmpty()) {
                unexpected.add(reply);
                continue;
            }
            // Each result is collected once, so two for one key means the key does not tell jobs
            // apart. Keeping either one would hide that; the first is named so it is not lost.
            JsonNode first = results.putIfAbsent(key.get(), reply);
            if (first != null) {
                throw new IllegalStateException(
                        "Two results for " + key.get() + ": first " + first + ", then " + reply + ".");
            }
        }
        return new Collected<>(results, List.copyOf(unexpected));
    }

    /** The expected keys with no result yet — what the transcript should be searched for. */
    private static <K> List<K> missing(Set<K> expected, Map<K, JsonNode> results) {
        return expected.stream().filter(k -> !results.containsKey(k)).toList();
    }
}
