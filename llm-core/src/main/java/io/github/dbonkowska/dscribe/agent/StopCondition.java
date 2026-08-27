package io.github.dbonkowska.dscribe.agent;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.ToolCall;

/**
 * What ends an {@link Agent} run: asked of every assistant turn, before anything in it is
 * dispatched.
 *
 * <p>The two shipped conditions differ in what they do to the turn they stop on. A run that ends
 * on a text turn has nothing left to do with it; a run that ends on a tool call deliberately
 * leaves that call unmade, because intercepting it *is* the point — the caller reads its
 * arguments off the turn.
 */
@FunctionalInterface
public interface StopCondition {

    boolean isTerminal(Message turn);

    /** Ends on the first turn the model spends replying rather than calling something. */
    static StopCondition untilNoToolCalls() {
        return turn -> !turn.hasToolCalls();
    }

    /** Ends the moment {@code name} is called, without dispatching it. */
    static StopCondition untilToolCalled(String name) {
        return turn -> turn.hasToolCalls()
                && turn.toolCalls().stream()
                        .map(ToolCall::function)
                        .anyMatch(function -> name.equals(function.name()));
    }
}
