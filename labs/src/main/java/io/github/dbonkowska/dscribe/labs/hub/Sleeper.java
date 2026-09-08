package io.github.dbonkowska.dscribe.labs.hub;

import java.time.Duration;

/**
 * Who does the waiting.
 *
 * <p>Injected so a test asserts the schedule instead of enduring it: the interesting question is
 * how long the loop decided to wait, and sleeping to find out would cost a minute per case.
 */
@FunctionalInterface
public interface Sleeper {

    void await(Duration duration);

    /**
     * Really waits.
     *
     * <p>Restores the interrupt flag before throwing. A Ctrl-C during a rate-limit wait is the
     * expected way to end a run that is sitting too long, and swallowing the flag would leave the
     * thread looking as though nothing had asked it to stop.
     */
    static Sleeper real() {
        return duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting out a rate limit", e);
            }
        };
    }
}
