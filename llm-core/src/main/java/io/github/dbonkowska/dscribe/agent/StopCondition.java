package io.github.dbonkowska.dscribe.agent;

import io.github.dbonkowska.dscribe.conversation.Message;

/**
 * What ends an {@link Agent} run: asked of every assistant turn, before anything in it is
 * dispatched.
 *
 * <p>The turn a condition stops on is appended untouched, so what it accepts decides whether the
 * conversation that comes back can seed another run. One that stops on a turn carrying tool calls
 * leaves them unanswered, and a provider rejects that; {@link #untilNoToolCalls} cannot.
 */
@FunctionalInterface
public interface StopCondition {

    boolean isTerminal(Message turn);

    /** Ends on the first turn the model spends replying rather than calling something. */
    static StopCondition untilNoToolCalls() {
        return turn -> !turn.hasToolCalls();
    }
}
