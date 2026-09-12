package io.github.dbonkowska.dscribe.tool;

/**
 * An image the model should be shown, named by URL.
 *
 * <p>A reference rather than the bytes: nothing here fetches it, the provider does that when it
 * builds the request. That keeps {@code llm-core} free of I/O, keeps a recorded exchange
 * readable — base64 would bury every request it appears in — and lets the same image be shown
 * again on a later turn for the cost of a URL.
 *
 * <p>All three still hold for an image that will not change, which is what a tool attachment is.
 * They do not hold for one whose bytes move under a fixed address: there the provider's later
 * fetch can substitute a different state for the one the tool observed. That case is served by
 * {@link io.github.dbonkowska.dscribe.conversation.ContentPart#image(String, byte[])}, which
 * carries the bytes and pins the moment — read this class's reasoning as the default, not as an
 * absolute.
 */
public record ImageRef(String url) {}
