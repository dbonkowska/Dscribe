package io.github.dbonkowska.dscribe.labs.hub;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The header names are not known when this is written — the exercise supplies them, and a reset
 * value may legally arrive as delta seconds, epoch seconds, epoch milliseconds or an HTTP date.
 * So this parses permissively, and every reading is wrong in a way nothing would report: a value
 * read in the wrong unit is a run that either hammers a limit it was told about or sits idle for
 * an hour.
 *
 * <p>The clamp is the load-bearing part. It is what makes guessing safe — a misread costs a
 * wrong-length wait rather than a hung run — and it holds if the API changes its mind later,
 * which no amount of reading one real response could.
 */
class RateLimitHeadersTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private static final RateLimitHeaders LIMITS =
            new RateLimitHeaders(List.of("X-Reset", "Retry-After"), Duration.ofSeconds(60));

    @Test
    void readsASmallNumberAsSecondsFromNow() {
        assertEquals(
                Optional.of(Duration.ofSeconds(30)),
                LIMITS.resetAfter(TestHeaders.of("X-Reset", "30"), NOW));
    }

    @Test
    void readsASecondsSizedTimestampAsAnInstant() {
        String at = String.valueOf(NOW.getEpochSecond() + 45);

        assertEquals(
                Optional.of(Duration.ofSeconds(45)),
                LIMITS.resetAfter(TestHeaders.of("X-Reset", at), NOW));
    }

    @Test
    void readsAMillisecondSizedTimestampAsAnInstant() {
        String at = String.valueOf(NOW.toEpochMilli() + 45_000);

        assertEquals(
                Optional.of(Duration.ofSeconds(45)),
                LIMITS.resetAfter(TestHeaders.of("X-Reset", at), NOW));
    }

    @Test
    void readsAnHttpDate() {
        String at = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(NOW.plusSeconds(20).atZone(ZoneId.of("GMT")));

        assertEquals(
                Optional.of(Duration.ofSeconds(20)),
                LIMITS.resetAfter(TestHeaders.of("X-Reset", at), NOW));
    }

    @Test
    void fallsToTheNextConfiguredNameWhenTheFirstIsAbsent() {
        assertEquals(
                Optional.of(Duration.ofSeconds(30)),
                LIMITS.resetAfter(TestHeaders.of("Retry-After", "30"), NOW));
    }

    @Test
    void treatsAValueItCannotReadAsAbsent() {
        assertEquals(Optional.empty(), LIMITS.resetAfter(TestHeaders.of("X-Reset", "soon"), NOW));
    }

    @Test
    void treatsBlankAndWhitespaceOnlyValuesAsAbsent() {
        assertEquals(Optional.empty(), LIMITS.resetAfter(TestHeaders.of("X-Reset", ""), NOW));
        assertEquals(Optional.empty(), LIMITS.resetAfter(TestHeaders.of("X-Reset", "   "), NOW));
    }

    @Test
    void hasNothingToSayWhenTheHeaderIsAbsent() {
        assertEquals(Optional.empty(), LIMITS.resetAfter(TestHeaders.of("X-Unrelated", "30"), NOW));
    }

    @Test
    void hasNothingToSayWhenNoNamesAreConfigured() {
        RateLimitHeaders unconfigured = new RateLimitHeaders(List.of(), Duration.ofSeconds(60));

        assertEquals(Optional.empty(), unconfigured.resetAfter(TestHeaders.of("X-Reset", "30"), NOW));
    }

    @Test
    void clampsAnAbsurdResetToTheCeiling() {
        String tenYearsOut = String.valueOf(NOW.getEpochSecond() + 315_360_000L);

        assertEquals(
                Optional.of(Duration.ofSeconds(60)),
                LIMITS.resetAfter(TestHeaders.of("X-Reset", tenYearsOut), NOW));
    }

    @Test
    void neverReportsANegativeWaitForAResetAlreadyPast() {
        String past = String.valueOf(NOW.getEpochSecond() - 45);

        assertEquals(Optional.empty(), LIMITS.resetAfter(TestHeaders.of("X-Reset", past), NOW));
    }
}
