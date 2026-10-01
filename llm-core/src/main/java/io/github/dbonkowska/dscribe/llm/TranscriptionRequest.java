package io.github.dbonkowska.dscribe.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What {@link LlmClient#transcribe} sends to the speech-to-text endpoint: the model and the audio,
 * carried as Base64 content rather than as an address.
 */
public record TranscriptionRequest(String model, @JsonProperty("input_audio") InputAudio inputAudio) {

    /**
     * @param data   the audio, Base64-encoded — raw, not a {@code data:} URI
     * @param format the container, e.g. {@code mp3} or {@code wav}
     */
    public record InputAudio(String data, String format) {}
}
