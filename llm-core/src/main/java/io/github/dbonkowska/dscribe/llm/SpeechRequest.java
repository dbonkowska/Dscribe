package io.github.dbonkowska.dscribe.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What {@link LlmClient#speak} sends to the text-to-speech endpoint.
 *
 * @param voice          the provider's voice id. The endpoint treats it as optional, but in practice
 *                       a request without one is refused by most providers
 * @param instructions   delivery hints such as tone; absent when there are none, since providers
 *                       that do not take them ignore them at best
 * @param responseFormat always {@code mp3}: the provider's default is PCM
 */
public record SpeechRequest(
        String model,
        String input,
        String voice,
        @JsonInclude(JsonInclude.Include.NON_NULL) String instructions,
        @JsonProperty("response_format") String responseFormat) {

    static final String MP3 = "mp3";
}
