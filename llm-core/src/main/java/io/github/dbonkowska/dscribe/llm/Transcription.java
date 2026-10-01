package io.github.dbonkowska.dscribe.llm;

/**
 * What a speech-to-text call heard, and what it cost.
 *
 * @param usage null when the reply reported none — absent, not zero
 */
public record Transcription(String text, Usage usage) {}
