package io.github.dbonkowska.dscribe.labs.hub;

import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Talks to a hub endpoint that fails on purpose: it retries what is worth retrying, and waits as
 * long as a refusal tells it to.
 *
 * <p>Wraps {@link HubSend} rather than living inside {@link HubClient}, so the single-shot call
 * every earlier lesson makes keeps its behaviour exactly. Nothing here is reached unless a caller
 * asks for it.
 *
 * <p>Purely reactive, and that is a finding rather than a simplification. The first design also
 * waited *ahead* of a call whenever a successful response reported its budget spent — which
 * never once happened across the real runs, because this API attaches rate-limit headers only to
 * refusals and never to a 200. So the shape of a run is: send, be refused, wait what the refusal
 * said, send again. Something that pre-empts the refusal would be better, and would need an API
 * that says enough on success to make it possible.
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
     * <p>Throws once the policy's attempts are spent — but note what happens to that exception in
     * this application rather than in principle. Its caller is a tool handler, and
     * {@code Toolbox} turns anything a handler throws into a tool result, so the model reads
     * "the hub kept refusing" as text and is free to call the tool again, spending a second full
     * cap. That is survivable because the agent's iteration cap bounds it, and it is preferable
     * to ending the run outright: a model told the API is refusing can decide to stop, where a
     * killed run cannot decide anything.
     *
     * @throws IllegalStateException when every attempt has been spent
     */
    public String call(String label, Object answer) {
        for (int attempt = 1; ; attempt++) {
            HubResponse response = hub.send(label + " · attempt " + attempt, taskName, answer);

            if (!response.isRetryable()) {
                return response.body();
            }

            Optional<Duration> backoff = retry.backoffAfter(attempt);
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
