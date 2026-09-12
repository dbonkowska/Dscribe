package io.github.dbonkowska.dscribe.conversation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inline image content, for the case a URL cannot represent: an artefact whose bytes change
 * under a fixed address. A URL sends the provider to fetch whatever is there *now*, which may
 * be a different state than the one the caller observed, and nothing downstream can tell that
 * it was.
 *
 * <p>Encoding is the whole of what can go wrong here, and it fails quietly — a mangled payload
 * is not rejected by the provider, it is decoded into a different image and reasoned over as if
 * it were the right one. So the assertion is a round trip rather than a prefix match.
 *
 * <p>How a part reaches the wire is {@link MessageTest}'s, not this class's: it already pins
 * that an image part carries no text key and vice versa.
 */
class ContentPartTest {

    /** A PNG signature with a high byte on the end — deliberately not valid UTF-8. */
    private static final byte[] BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, (byte) 0xFF};

    @Test
    void carriesBytesAsADataUriThatDecodesBackToExactlyThoseBytes() {
        ContentPart part = ContentPart.image("image/png", BYTES);

        String url = part.imageUrl().url();
        String prefix = "data:image/png;base64,";

        assertTrue(url.startsWith(prefix), () -> "expected a png data uri, got: " + url);
        assertArrayEquals(
                BYTES,
                Base64.getDecoder().decode(url.substring(prefix.length())),
                "a mangled payload is decoded into a different image rather than refused");
    }

    @Test
    void declaresItselfAnImagePartAndCarriesNoText() {
        ContentPart part = ContentPart.image("image/png", BYTES);

        assertEquals("image_url", part.type());
        assertNull(part.text(), "a part offering both keys is rejected by the provider");
    }

    @Test
    void leavesTheUrlFormAlone() {
        ContentPart part = ContentPart.image("https://e/x.png");

        assertEquals("image_url", part.type());
        assertEquals("https://e/x.png", part.imageUrl().url());
        assertNull(part.text());
    }

    @Test
    void honoursTheMediaTypeItIsGiven() {
        String url = ContentPart.image("image/jpeg", "x".getBytes(StandardCharsets.UTF_8)).imageUrl().url();

        assertTrue(url.startsWith("data:image/jpeg;base64,"), () -> url);
    }
}
