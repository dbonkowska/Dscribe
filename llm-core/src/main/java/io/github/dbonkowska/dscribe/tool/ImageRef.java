package io.github.dbonkowska.dscribe.tool;

/**
 * An image the model should be shown, named by URL.
 *
 * <p>A reference rather than the bytes: nothing here fetches it, the provider does that when it
 * builds the request. That keeps {@code llm-core} free of I/O, keeps a recorded exchange
 * readable — base64 would bury every request it appears in — and lets the same image be shown
 * again on a later turn for the cost of a URL.
 */
public record ImageRef(String url) {}
