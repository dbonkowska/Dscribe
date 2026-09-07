package io.github.dbonkowska.dscribe.tool;

import io.github.dbonkowska.dscribe.conversation.Message;

import java.util.List;

/**
 * Everything one dispatched call contributes to the conversation, split by where each part has
 * to go.
 *
 * <p>{@code result} answers the call id and belongs with the other tool results of its turn.
 * {@code attachments} must follow <em>all</em> of them: a {@code user} message sitting between
 * two tool results makes the next request invalid at the provider, the same failure class as a
 * call left unanswered. The split is what makes that ordering visible to a caller; nothing here
 * enforces it, because whole-turn dispatch would have to move into {@link Toolbox} and take the
 * per-call logging and the refused-answer path with it.
 *
 * <p>Not the same thing as a {@link ToolOutput} one layer up: the unknown-tool, bad-argument and
 * failed-handler paths all produce one of these with no {@code ToolOutput} behind it.
 */
public record ToolCallMessages(Message result, List<Message> attachments) {

    /** A call that produced nothing to look at — every path but a handler returning an image. */
    static ToolCallMessages of(Message result) {
        return new ToolCallMessages(result, List.of());
    }
}
