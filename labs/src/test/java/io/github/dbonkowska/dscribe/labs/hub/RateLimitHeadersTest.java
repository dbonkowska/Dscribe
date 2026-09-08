package io.github.dbonkowska.dscribe.labs.hub;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The header names are not known when this is written — the exercise supplies them, and a reset
 * value may legally arrive as delta seconds, epoch seconds, epoch milliseconds or an HTTP date.
 * So this parses permissively and every reading is wrong in a way nothing would report: a value
 * read in the wrong unit is a run that either hammers a limit it was told about or sits idle for
 * an hour.
 *
 * <p>The clamp is the load-bearing part. It is what makes guessing safe — a misread costs a
 * wrong-length wait rather than a hung run — and it holds if the API changes its mind later,
 * which no amount of reading one real response could.
 */
class RateLimitHeadersTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private static final RateLimitHeaders LIMITS = new RateLimitHeaders(
            List.of("X-Reset", "Retry-After"), List.of("X-Remaining"), Duration.ofSeconds(60));

    // --- reading the reset value ------------------------------------------------------------

    @Test
    void readsASmallNumberAsSecondsFromNow() {
        assertEquals(Optional.of(Duration.ofSeconds(30)), LIMITS.resetAfter(headers("X-Reset", "30"), NOW));
    }

    @Test
    void readsASecondsSizedTimestampAsAnInstant() {
        String at = String.valueOf(NOW.getEpochSecond() + 45);

        assertEquals(Optional.of(Duration.ofSeconds(45)), LIMITS.resetAfter(headers("X-Reset", at), NOW));
    }

    @Test
    void readsAMillisecondSizedTimestampAsAnInstant() {
        String at = String.valueOf(NOW.toEpochMilli() + 45_000);

        assertEquals(Optional.of(Duration.ofSeconds(45)), LIMITS.resetAfter(headers("X-Reset", at), NOW));
    }

    @Test
    void readsAnHttpDate() {
        String at = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(NOW.plusSeconds(20).atZone(ZoneId.of("GMT")));

        assertEquals(Optional.of(Duration.ofSeconds(20)), LIMITS.resetAfter(headers("X-Reset", at), NOW));
    }

    @Test
    void fallsToTheNextConfiguredNameWhenTheFirstIsAbsent() {
        assertEquals(
                Optional.of(Duration.ofSeconds(30)),
                LIMITS.resetAfter(headers("Retry-After", "30"), NOW));
    }

    @Test
    void treatsAValueItCannotReadAsAbsent() {
        assertEquals(Optional.empty(), LIMITS.resetAfter(headers("X-Reset", "soon"), NOW));
    }

    @Test
    void treatsBlankAndWhitespaceOnlyValuesAsAbsent() {
        assertEquals(Optional.empty(), LIMITS.resetAfter(headers("X-Reset", ""), NOW));
        assertEquals(Optional.empty(), LIMITS.resetAfter(headers("X-Reset", "   "), NOW));
    }

    @Test
    void hasNothingToSayWhenTheHeaderIsAbsent() {
        assertEquals(Optional.empty(), LIMITS.resetAfter(headers("X-Unrelated", "30"), NOW));
    }

    @Test
    void hasNothingToSayWhenNoNamesAreConfigured() {
        RateLimitHeaders unconfigured =
                new RateLimitHeaders(List.of(), List.of(), Duration.ofSeconds(60));

        assertEquals(Optional.empty(), unconfigured.resetAfter(headers("X-Reset", "30"), NOW));
    }

    @Test
    void clampsAnAbsurdResetToTheCeiling() {
        String tenYearsOut = String.valueOf(NOW.getEpochSecond() + 315_360_000L);

        assertEquals(
                Optional.of(Duration.ofSeconds(60)),
                LIMITS.resetAfter(headers("X-Reset", tenYearsOut), NOW));
    }

    @Test
    void neverReportsANegativeWaitForAResetAlreadyPast() {
        String past = String.valueOf(NOW.getEpochSecond() - 45);

        assertEquals(Optional.empty(), LIMITS.resetAfter(headers("X-Reset", past), NOW));
    }

    // --- deciding whether to wait proactively ------------------------------------------------

    @Test
    void doesNotWaitWhileBudgetRemains() {
        HttpHeaders headers = headers(Map.of("X-Reset", "30", "X-Remaining", "5"));

        assertEquals(Optional.empty(), LIMITS.waitAfter(headers, NOW));
    }

    @Test
    void waitsForTheResetOnceTheBudgetIsSpent() {
        HttpHeaders headers = headers(Map.of("X-Reset", "30", "X-Remaining", "0"));

        assertEquals(Optional.of(Duration.ofSeconds(30)), LIMITS.waitAfter(headers, NOW));
    }

    /**
     * The case that keeps a run moving against an API that reports no budget at all. Waiting for
     * every reset would serialise the whole run to one call per window; the defence against a
     * limit nobody announced is reacting to the rejection, not pre-emptive sleeping.
     */
    @Test
    void doesNotWaitWhenNothingReportsARemainingBudget() {
        assertEquals(Optional.empty(), LIMITS.waitAfter(headers("X-Reset", "30"), NOW));
    }

    @Test
    void hasNothingToWaitForWhenTheBudgetIsSpentButNoResetIsGiven() {
        assertEquals(Optional.empty(), LIMITS.waitAfter(headers("X-Remaining", "0"), NOW));
    }

    private static HttpHeaders headers(String name, String value) {
        return headers(Map.of(name, value));
    }

    private static HttpHeaders headers(Map<String, String> values) {
        return HttpHeaders.of(
                values.entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()))),
                (name, value) -> true);
    }
}
