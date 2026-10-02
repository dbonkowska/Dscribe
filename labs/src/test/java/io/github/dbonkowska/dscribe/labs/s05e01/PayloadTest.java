package io.github.dbonkowska.dscribe.labs.s05e01;

import io.github.dbonkowska.dscribe.labs.s05e01.Payload.Dropped;
import io.github.dbonkowska.dscribe.labs.s05e01.Payload.Route;
import io.github.dbonkowska.dscribe.labs.s05e01.Payload.Routed;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tested although the hub is the oracle, because a wrong verdict here is silent. A valid file
 * dropped as corrupt never reaches a model, and the run reads afterwards as "the material did not
 * say", which sends the reader to the prompt instead of to this check.
 *
 * <p>Fixtures are invented: a few bytes with the right header, never a real file.
 */
class PayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] MP3_TAGGED = {'I', 'D', '3', 4, 0, 0};
    private static final byte[] MP3_FRAME = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64};

    private static ObjectNode attachment(String meta, byte[] bytes) {
        return attachment(meta, Base64.getEncoder().encodeToString(bytes), bytes.length);
    }

    private static ObjectNode attachment(String meta, String base64, int filesize) {
        ObjectNode reply = MAPPER.createObjectNode();
        reply.put("code", 100);
        reply.put("meta", meta);
        reply.put("attachment", base64);
        reply.put("filesize", filesize);
        return reply;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Routed routed(Payload payload) {
        return assertInstanceOf(Routed.class, payload, () -> "expected routed, got " + payload);
    }

    private static void droppedFor(Payload payload, String reasonMentions) {
        Dropped dropped = assertInstanceOf(Dropped.class, payload, () -> "expected dropped, got " + payload);
        assertTrue(dropped.reason().contains(reasonMentions),
                () -> "the reason has to say why: " + dropped.reason());
    }

    @Test
    void routesATranscriptionAsText() {
        ObjectNode reply = MAPPER.createObjectNode().put("code", 100).put("transcription", "abc");

        Routed routed = routed(Payload.classify(4, reply));

        assertEquals(4, routed.number());
        assertEquals(Route.TEXT, routed.route());
        assertEquals("transcription", routed.kind());
        assertEquals("abc", routed.text());
    }

    @Test
    void routesStructuredTextAttachmentsAsTheirDecodedText() {
        Routed csv = routed(Payload.classify(1, attachment("text/csv", utf8("a,b\n1,ż"))));
        Routed xml = routed(Payload.classify(2, attachment("text/xml", utf8("<a>b</a>"))));
        Routed json = routed(Payload.classify(3, attachment("application/json", utf8("{\"a\":1}"))));

        assertEquals(Route.TEXT, csv.route());
        assertEquals("a,b\n1,ż", csv.text());
        assertEquals("text/csv", csv.kind());
        assertEquals(Route.TEXT, xml.route());
        assertEquals("<a>b</a>", xml.text());
        assertEquals(Route.TEXT, json.route());
        assertEquals("{\"a\":1}", json.text());
    }

    @Test
    void routesImagesToVisionWithTheirBytes() {
        Routed jpeg = routed(Payload.classify(1, attachment("image/jpeg", JPEG)));
        Routed png = routed(Payload.classify(2, attachment("image/png", PNG)));

        assertEquals(Route.VISION, jpeg.route());
        assertArrayEquals(JPEG, jpeg.bytes());
        assertEquals(Route.VISION, png.route());
        assertArrayEquals(PNG, png.bytes());
    }

    /** An MP3 opens with an ID3 tag or straight on a frame; both are real files. */
    @Test
    void routesAudioToSpeechWhetherItOpensOnATagOrAFrame() {
        assertEquals(Route.SPEECH, routed(Payload.classify(1, attachment("audio/mpeg", MP3_TAGGED))).route());
        assertEquals(Route.SPEECH, routed(Payload.classify(2, attachment("audio/mpeg", MP3_FRAME))).route());
    }

    @Test
    void dropsAFileWhoseSizeDisagreesWithItsDeclaration() {
        droppedFor(Payload.classify(1, attachment("image/png", Base64.getEncoder().encodeToString(PNG), 10)), "size");
    }

    @Test
    void dropsAFileWhoseBytesAreNotTheDeclaredType() {
        droppedFor(Payload.classify(1, attachment("image/jpeg", PNG)), "image/jpeg");
    }

    @Test
    void dropsJsonThatDoesNotParse() {
        droppedFor(Payload.classify(1, attachment("application/json", utf8("{\"a\":"))), "JSON");
    }

    @Test
    void dropsXmlThatDoesNotParse() {
        droppedFor(Payload.classify(1, attachment("text/xml", utf8("<a>"))), "XML");
    }

    /** A lenient decoder would turn this into a replacement character and route it as text. */
    @Test
    void dropsTextThatIsNotValidUtf8() {
        droppedFor(Payload.classify(1, attachment("text/csv", new byte[] {(byte) 0xC3, 0x28})), "UTF-8");
    }

    @Test
    void dropsATypeWithNoRoute() {
        droppedFor(Payload.classify(1, attachment("application/zip", new byte[] {'P', 'K', 3, 4})), "no route");
    }

    @Test
    void dropsAnAttachmentThatIsNotBase64() {
        droppedFor(Payload.classify(1, attachment("text/csv", "@@@", 3)), "Base64");
    }

    @Test
    void dropsAReplyCarryingNeitherKindOfPayload() {
        Payload payload = Payload.classify(1, MAPPER.createObjectNode().put("code", 100));

        droppedFor(payload, "transcription");
        droppedFor(payload, "attachment");
    }
}
