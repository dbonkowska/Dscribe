package io.github.dbonkowska.dscribe.agent;

import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.ToolCall;

import java.util.Optional;

/**
 * What ends an {@link Agent} run on a tool's result: asked after each result is appended, with the
 * call that produced it.
 *
 * <p>Empty means keep going. A present value ends the run at once and is what the run returns —
 * calls the same turn still owes are not dispatched, because whatever they would have sent lands
 * after the run was already over.
 *
 * @param <T> what the run hands back
 */
@FunctionalInterface
public interface ResultObserver<T> {

    Optional<T> observe(ToolCall call, Message result);
}
