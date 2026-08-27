package io.github.dbonkowska.dscribe.labs.session;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The failure this exists to prevent is session bleed: one caller's conversation turning up in
 * another's, or a history changing under the store because somebody kept the list they handed in.
 * Neither throws. The model simply answers from a context nobody sent it, and the only symptom is
 * a reply that reads as if it were meant for someone else.
 *
 * <p>Nothing here touches the filesystem or the network — the store is a map, and a test that
 * needed a fixture on disk would be testing something this class does not do.
 */
class SessionStoreTest {

    private static final Message HELLO = new Message(Role.user, "hello");
    private static final Message THERE = new Message(Role.assistant, "there");

    @Test
    void handsBackAnEmptyHistoryForASessionItHasNeverSeen() {
        // the first request of a conversation arrives with an id the store has no entry for;
        // anything but an empty list here leaves the caller seeding a run with null
        assertEquals(List.of(), new SessionStore().load("unknown"));
    }

    @Test
    void roundTripsAHistoryInOrder() {
        SessionStore store = new SessionStore();

        store.save("a", List.of(HELLO, THERE));

        assertEquals(List.of(HELLO, THERE), store.load("a"));
    }

    @Test
    void keepsOneSessionsHistoryOutOfAnothers() {
        SessionStore store = new SessionStore();

        store.save("a", List.of(HELLO));
        store.save("b", List.of(THERE));

        assertEquals(List.of(HELLO), store.load("a"));
        assertEquals(List.of(THERE), store.load("b"));
    }

    @Test
    void ownsItsCopyOfEveryHistoryItHolds() {
        // the caller's list goes on growing after the save — the agent loop hands over the very
        // ArrayList it looped on — and what comes back out must not follow it
        SessionStore store = new SessionStore();
        List<Message> saved = new ArrayList<>(List.of(HELLO));
        store.save("a", saved);

        saved.add(THERE);

        assertEquals(List.of(HELLO), store.load("a"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> store.load("a").add(THERE),
                "what load hands out is the stored list itself, and refuses to be written to");
    }
}
