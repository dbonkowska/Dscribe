package io.github.dbonkowska.dscribe.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Base64;

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

    /**
     * An image carried as content rather than as an address, encoded into a {@code data:} URI.
     *
     * <p>For the case {@link #image(String)} cannot represent: an artefact whose bytes change
     * under a fixed URL. There the provider's own fetch is the problem — it happens later than
     * the read that decided to send the image, so it can return a different state than the one
     * the caller saw, with nothing downstream able to tell that it did. Sending the bytes pins
     * the moment.
     *
     * <p>No I/O: the bytes arrive from the caller, already read. A payload this produces is large
     * enough to bury whatever records the request, so anything keeping a copy of one should
     * elide it rather than write it whole.
     */
    public static ContentPart image(String mediaType, byte[] bytes) {
        return image("data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes));
    }
}
