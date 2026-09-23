package io.github.dbonkowska.dscribe.labs.s03e04;

import io.github.dbonkowska.dscribe.labs.hub.Sleeper;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the hub for a result that is prepared in the background, until it is there.
 *
 * <p>Decided on the body and never on the status. The hub answers a check that is not ready with
 * an ordinary success status and a message saying so, which is the same status it uses for the
 * result. A poll that stopped on the status stopped on its first check and recorded "not yet" as
 * the run's outcome.
 *
 * <p>The only exits are a body carrying the result and running out of attempts. The wait comes
 * before each check, since a check sent the moment the tools were registered can only be early.
 */
final class ResultPoll {

    private final Supplier<String> check;
    private final Pattern result;
    private final Sleeper sleeper;
    private final int attempts;
    private final Duration interval;

    ResultPoll(Supplier<String> check, Pattern result, Sleeper sleeper, int attempts, Duration interval) {
        this.check = check;
        this.result = result;
        this.sleeper = sleeper;
        this.attempts = attempts;
        this.interval = interval;
    }

    /** @return the result as matched, or empty once every attempt has come back without one */
    Optional<String> run() {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            sleeper.await(interval);

            Matcher found = result.matcher(check.get());
            if (found.find()) {
                return Optional.of(found.group());
            }
        }
        return Optional.empty();
    }
}
