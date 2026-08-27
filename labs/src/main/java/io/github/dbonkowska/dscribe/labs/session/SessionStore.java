package io.github.dbonkowska.dscribe.labs.session;

import io.github.dbonkowska.dscribe.conversation.Message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversation history per session id, in memory, for as long as the process runs.
 *
 * <p>The agent loop remembers nothing between calls — a run is seeded with a conversation and
 * hands one back. Anything serving more than one caller has to keep those apart, and the failure
 * when it doesn't is silent: no exception, just a model answering out of a context that belongs
 * to somebody else. So a history goes in copied and comes out copied, and the id is the only way
 * to reach one.
 *
 * <p>Concurrent because the server it backs is: a handler runs on whichever thread its request
 * arrived on, and two sessions can be mid-run at once.
 */
public final class SessionStore {

    private final Map<String, List<Message>> histories = new ConcurrentHashMap<>();

    /** The conversation so far, or an empty list for an id that has never been saved. */
    public List<Message> load(String sessionId) {
        return histories.getOrDefault(sessionId, List.of());
    }

    /** Replaces whatever {@code sessionId} held with a copy of {@code history}. */
    public void save(String sessionId, List<Message> history) {
        histories.put(sessionId, List.copyOf(history));
    }
}
