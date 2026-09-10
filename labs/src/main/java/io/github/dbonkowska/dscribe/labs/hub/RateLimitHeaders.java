package io.github.dbonkowska.dscribe.labs.hub;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * What a refusal's headers say about when to come back.
 *
 * <p>The names come from the lesson bundle rather than from here. The exercise supplies them,
 * which puts them under the same rule as any other task parameter — and it is also what lets the
 * first real run settle them, since nothing documents them in advance.
 *
 * <p>Values are read permissively, because a reset can legally arrive in four shapes and nothing
 * says which one to expect. That guessing is made safe by {@code maxWait} rather than by being
 * right: a misread costs a wrong-length wait, never a hung run, and the clamp keeps holding if
 * the API changes shape later.
 *
 * <p>This reads only what a response volunteers. It once also decided whether to wait *before* a
 * later call, on the strength of a remaining-budget header — a mechanism the runs then showed
 * could never fire, because this API reports its budget only when refusing and never on success.
 * It was deleted rather than kept for an API that might behave otherwise.
 *
 * @param resetHeaders names that may carry when the budget refills, most preferred first
 * @param maxWait      the ceiling every computed wait is clamped to
 */
public record RateLimitHeaders(List<String> resetHeaders, Duration maxWait) {

    /** Above this many, a number is a timestamp in milliseconds rather than a count of them. */
    private static final long MILLIS_TIMESTAMP = 1_000_000_000_000L;

    /** Above this many, a number is a timestamp in seconds rather than a count of seconds. */
    private static final long SECONDS_TIMESTAMP = 1_000_000_000L;

    /**
     * How long until the budget refills, whatever shape the header said it in. Empty when nothing
     * says, including when the value cannot be read at all — an unreadable header is treated as
     * absent rather than guessed at.
     *
     * <p>A server that has just refused a call is a better authority on when to come back than
     * any local schedule, so this takes precedence over one wherever it answers.
     */
    public Optional<Duration> resetAfter(HttpHeaders headers, Instant now) {
        return firstPresent(headers, resetHeaders)
                .flatMap(value -> parse(value, now))
                .map(this::clamped)
                .filter(wait -> !wait.isZero());
    }

    /** The first of the configured names that is actually there, blank counting as absent. */
    private static Optional<String> firstPresent(HttpHeaders headers, List<String> names) {
        return names.stream()
                .map(headers::firstValue)
                .flatMap(Optional::stream)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    /**
     * A reset in one of its four legal shapes. The numeric ones separate by magnitude with four
     * orders of magnitude of clear air between them — a delta of a billion seconds is thirty-one
     * years, so no plausible delta reaches the band where timestamps live.
     */
    private static Optional<Duration> parse(String value, Instant now) {
        Optional<Long> number = asLong(value);
        if (number.isPresent()) {
            long raw = number.get();
            if (raw > MILLIS_TIMESTAMP) {
                return Optional.of(Duration.between(now, Instant.ofEpochMilli(raw)));
            }
            if (raw > SECONDS_TIMESTAMP) {
                return Optional.of(Duration.between(now, Instant.ofEpochSecond(raw)));
            }
            return Optional.of(Duration.ofSeconds(raw));
        }
        return httpDate(value, now);
    }

    private static Optional<Duration> httpDate(String value, Instant now) {
        try {
            ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Optional.of(Duration.between(now, at.toInstant()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Long> asLong(String value) {
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Into {@code [0, maxWait]}: a reset already past is not a reason to wait, or to go back. */
    private Duration clamped(Duration wait) {
        if (wait.isNegative()) {
            return Duration.ZERO;
        }
        return wait.compareTo(maxWait) > 0 ? maxWait : wait;
    }
}
