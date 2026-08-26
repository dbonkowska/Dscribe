package io.github.dbonkowska.dscribe.conversation;

/**
 * One tool invocation the model asked for, as it arrives on an assistant turn and as it is
 * echoed back in the conversation.
 */
public record ToolCall(String id, String type, Invocation function) {

    /**
     * @param arguments the raw JSON text the model produced. Kept a {@code String} rather than
     *                  parsed into a node: the same assistant turn goes back to the provider on
     *                  the next round-trip, and re-serialising a parsed object would rewrite
     *                  key order and number formatting. Parsing is the toolbox's job.
     */
    public record Invocation(String name, String arguments) {}
}