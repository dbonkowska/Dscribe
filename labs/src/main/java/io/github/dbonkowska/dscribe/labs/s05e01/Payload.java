package io.github.dbonkowska.dscribe.labs.s05e01;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * One reply from the session, sorted by code before any model sees it: routed to the cheapest
 * reader that can turn it into text, or dropped with the reason.
 *
 * <p>Every check here is about the file, not the task. Magic bytes and parsers are format facts,
 * the same for any exercise, so they live in Java; what the material <em>says</em> is the models'.
 *
 * <p>A dropped payload is logged, never retried: the cascade already copes with a source that is
 * missing, and a fresh session would cost a full listen for one file.
 */
sealed interface Payload {

    /** Where a payload goes to become text: read as it is, transcribed, or described. */
    enum Route { TEXT, SPEECH, VISION }

    /**
     * @param kind  {@code transcription}, or the attachment's declared media type
     * @param text  the payload as text, for {@link Route#TEXT}; null otherwise
     * @param bytes the decoded file, for {@link Route#SPEECH} and {@link Route#VISION}; null otherwise
     */
    record Routed(int number, Route route, String kind, String text, byte[] bytes) implements Payload {}

    record Dropped(int number, String kind, String reason) implements Payload {}

    /** What each routed media type is, and how to recognise its bytes. */
    Map<String, Route> ROUTES = Map.of(
            "text/csv", Route.TEXT,
            "text/xml", Route.TEXT,
            "application/json", Route.TEXT,
            "image/jpeg", Route.VISION,
            "image/png", Route.VISION,
            "audio/mpeg", Route.SPEECH);

    ObjectMapper MAPPER = new ObjectMapper();

    static Payload classify(int number, JsonNode reply) {
        if (reply.hasNonNull("transcription")) {
            return new Routed(number, Route.TEXT, "transcription", reply.path("transcription").asString(""), null);
        }
        if (!reply.hasNonNull("attachment")) {
            return new Dropped(number, "unknown", "the reply carries neither a transcription nor an attachment");
        }

        String meta = reply.path("meta").asString("");
        Route route = ROUTES.get(meta);
        if (route == null) {
            return new Dropped(number, meta, "no route for media type '" + meta + "'");
        }

        byte[] bytes;
        try {
            // Line breaks a sender may insert are removed, then the strict decoder runs. Not the MIME
            // decoder: it skips every character outside the alphabet, so garbage decodes to fewer
            // bytes and reads as a size mismatch instead of as what it is.
            bytes = Base64.getDecoder().decode(reply.path("attachment").asString("").replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            return new Dropped(number, meta, "the attachment is not valid Base64: " + e.getMessage());
        }

        int declared = reply.path("filesize").asInt(-1);
        if (bytes.length != declared) {
            return new Dropped(number, meta,
                    "decoded size " + bytes.length + " differs from the declared filesize " + declared);
        }

        if (route != Route.TEXT) {
            return startsAs(meta, bytes)
                    ? new Routed(number, route, meta, null, bytes)
                    : new Dropped(number, meta, "the bytes do not start as " + meta + " does");
        }

        String text;
        try {
            // strict: a lenient decode turns bad bytes into replacement characters and routes them on
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new Dropped(number, meta, "the text is not valid UTF-8");
        }

        String unparsed = switch (meta) {
            case "application/json" -> jsonProblem(text);
            case "text/xml" -> xmlProblem(text);
            default -> null;
        };
        return unparsed == null
                ? new Routed(number, route, meta, text, null)
                : new Dropped(number, meta, unparsed);
    }

    /** The leading bytes each binary type opens with. An MP3 starts on an ID3 tag or on a frame. */
    private static boolean startsAs(String meta, byte[] bytes) {
        return switch (meta) {
            case "image/jpeg" -> opensWith(bytes, 0xFF, 0xD8, 0xFF);
            case "image/png" -> opensWith(bytes, 0x89, 0x50, 0x4E, 0x47);
            case "audio/mpeg" -> opensWith(bytes, 'I', 'D', '3')
                    || (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0);
            default -> false;
        };
    }

    private static boolean opensWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String jsonProblem(String text) {
        try {
            MAPPER.readTree(text);
            return null;
        } catch (JacksonException e) {
            return "the JSON does not parse: " + e.getOriginalMessage();
        }
    }

    /** Doctype declarations refused, so a file from outside cannot make the parser fetch anything. */
    private static String xmlProblem(String text) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            builder.parse(new InputSource(new StringReader(text)));
            return null;
        } catch (SAXException | IOException e) {
            return "the XML does not parse: " + e.getMessage();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }
}
