package io.github.dbonkowska.dscribe.labs.hub;

import java.net.http.HttpHeaders;

/**
 * One attempt at the hub, whole.
 *
 * <p>Exists because {@code verify} returned only the body, which is enough for an endpoint that
 * either accepts an answer or does not, and not enough for one that fails on purpose: the status
 * says whether to come back, and the headers say when.
 */
public record HubResponse(int status, HttpHeaders headers, String body) {

    /**
     * Whether coming back is worth trying — a server error, or a refusal for asking too often.
     *
     * <p>Not 4xx generally. A rejected answer is a wrong answer however many times it is sent,
     * and retrying it would spend the budget proving that.
     */
    public boolean isRetryable() {
        return status >= 500 || status == 429;
    }
}
