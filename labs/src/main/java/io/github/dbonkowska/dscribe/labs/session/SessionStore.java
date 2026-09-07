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
 * to somebody else. So a history goes in copied, comes back out shared but unmodifiable, and the
 * id is the only way to reach one.
 *
 * <p>A concurrent map even though the one server using it today runs handlers one at a time —
 * s01e03 gives its {@code HttpServer} no executor, on purpose. That is the server's decision to
 * revisit, and a store that were only safe single-threaded would become the reason it couldn't
 * be: adding an executor would corrupt the map rather than merely widening it.
 *
 * <p>The map being concurrent is not the same as a session being safe under parallel handlers.
 * A turn is load, run, save, and nothing holds the id across the three — two requests on one
 * session at once would each seed from the same history and the later save would win, losing
 * the other's turn. Out of scope until a lesson serves one session concurrently.
 */
public final class SessionStore {

    private final Map<String, List<Message>> histories = new ConcurrentHashMap<>();

    /**
     * The conversation so far, or an empty list for an id that has never been saved. What comes
     * back is the stored list itself and is unmodifiable — a caller that tries to append to it
     * fails loudly rather than mutating a session, or quietly editing a copy and believing it
     * worked.
     */
    public List<Message> load(String sessionId) {
        return histories.getOrDefault(sessionId, List.of());
    }

    /** Replaces whatever {@code sessionId} held with a copy of {@code history}. */
    public void save(String sessionId, List<Message> history) {
        histories.put(sessionId, List.copyOf(history));
    }
}
