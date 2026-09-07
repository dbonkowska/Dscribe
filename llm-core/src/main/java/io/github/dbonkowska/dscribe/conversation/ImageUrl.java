package io.github.dbonkowska.dscribe.conversation;

/**
 * Where an image lives. Nothing in {@code llm-core} dereferences it — the provider fetches the
 * URL when it builds the request it sends to the model.
 */
public record ImageUrl(String url) {}
