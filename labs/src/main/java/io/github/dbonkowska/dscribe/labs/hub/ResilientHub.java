package io.github.dbonkowska.dscribe.labs.hub;

import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Talks to a hub endpoint that fails on purpose: it retries what is worth retrying, and waits out
 * a request budget rather than spending it.
 *
 * <p>Wraps {@link HubSend} rather than living inside {@link HubClient}, so the single-shot call
 * every earlier lesson makes keeps its behaviour exactly. Nothing here is reached unless a caller
 * asks for it.
 *
 * <p>Two kinds of wait, and the difference matters. A **reactive** one follows a refusal: the
 * server has just said no, and if it said when to come back that beats any local schedule. A
 * **proactive** one is carried to the *next* call after a response reported the budget spent —
 * before the request, never after the last one, because waiting out a reset nobody is waiting on
 * is pure delay.
 *
 * <p>Nothing bounds the total elapsed time of a run. A cap could kill one seconds from success
 * and waste everything already spent; the INFO line before each wait is what makes a long sit
 * visible, and the transcript is written as it goes, so a Ctrl-C still leaves a complete file.
 */
public final class ResilientHub {

    private static final Logger log = LoggerFactory.getLogger(ResilientHub.class);

    private final HubSend hub;
    private final String taskName;
    private final RetryPolicy retry;
    private final RateLimitHeaders limits;
    private final Sleeper sleeper;
    private final RunTranscript transcript;

    /** What the previous call was told to wait before the next one, or null when it said nothing. */
    private Duration pending;

    public ResilientHub(
            HubSend hub,
            String taskName,
            RetryPolicy retry,
            RateLimitHeaders limits,
            Sleeper sleeper,
            RunTranscript transcript) {

        this.hub = hub;
        this.taskName = taskName;
        this.retry = retry;
        this.limits = limits;
        this.sleeper = sleeper;
        this.transcript = transcript;
    }

    /**
     * Sends {@code answer} and returns the body, retrying while the endpoint says to come back.
     *
     * @throws IllegalStateException once the policy's attempts are spent. Nothing is swallowed:
     *                               a caller that ran out of attempts needs to hear about it
     *                               rather than read an error body as an answer.
     */
    public String call(String label, Object answer) {
        payAnyDebt();

        for (int attempt = 1; ; attempt++) {
            HubResponse response = hub.send(label + " · attempt " + attempt, taskName, answer);

            if (!response.isRetryable()) {
                // carried, not taken: this call is done, and the budget is owed before the next
                pending = limits.waitAfter(response.headers(), Instant.now()).orElse(null);
                return response.body();
            }

            Optional<Duration> backoff = retry.backoffBefore(attempt + 1);
            if (backoff.isEmpty()) {
                throw new IllegalStateException(
                        "The hub kept refusing " + label + " after " + attempt
                                + " attempts; last status " + response.status());
            }

            // the server's own number wins where it gave one
            Duration wait = limits.resetAfter(response.headers(), Instant.now()).orElse(backoff.get());
            waitOut(wait, "status " + response.status() + " on attempt " + attempt);
        }
    }

    private void payAnyDebt() {
        if (pending != null) {
            Duration owed = pending;
            pending = null;
            waitOut(owed, "request budget spent");
        }
    }

    /**
     * Logged before the wait rather than after it. A silent minute reads as a hung run and invites
     * a Ctrl-C on something that was working; the same line after the fact explains a pause the
     * operator has already given up on.
     */
    private void waitOut(Duration wait, String reason) {
        log.info("waiting {}s — {}", wait.toSeconds(), reason);
        transcript.hubWait(reason, wait);
        sleeper.await(wait);
    }
}
