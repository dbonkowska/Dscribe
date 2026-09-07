package io.github.dbonkowska.dscribe.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One piece of a multipart message. A part carries exactly one payload: {@code text} for a
 * {@code "text"} part, {@code imageUrl} for an {@code "image_url"} one, and {@code NON_NULL}
 * keeps the unused sibling off the wire — a part offering both keys is rejected by the provider
 * rather than ignored.
 *
 * <p>Outbound only. The model answers in text, so nothing ever deserialises into this.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPart(
        String type,
        String text,
        @JsonProperty("image_url") ImageUrl imageUrl) {

    public static ContentPart text(String text) {
        return new ContentPart("text", text, null);
    }

    public static ContentPart image(String url) {
        return new ContentPart("image_url", null, new ImageUrl(url));
    }
}
