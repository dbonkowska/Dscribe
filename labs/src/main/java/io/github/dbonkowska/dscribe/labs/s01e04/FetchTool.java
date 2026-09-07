package io.github.dbonkowska.dscribe.labs.s01e04;

import io.github.dbonkowska.dscribe.tool.ImageRef;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Retrieves whatever a URL points at, and hands the model the form it can actually read: text
 * comes back as the tool result, an image comes back as an attachment.
 *
 * <p>Which one is decided by the response's {@code Content-Type}, and that is the fragile part of
 * this class. A server that mislabels a document as an image gets it silently shown rather than
 * read, and the reverse pushes binary into the conversation as text. Nothing here can tell the
 * difference, so a first run is worth reading in the transcript rather than assuming.
 *
 * <p>Bigger than the tool factories in the earlier lessons, which is why it is its own class
 * rather than a private method on the runner.
 */
final class FetchTool {

    /** The model supplies the URL, so this is the whole schema the model sees. */
    record Fetch(String url) {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private FetchTool() {}

    /** Name and description come from the lesson bundle: they are prompt surface. */
    static Tool<Fetch> of(String name, String description) {
        return new Tool<>(name, description, Fetch.class, args -> retrieve(args.url()));
    }

    /**
     * A failure throws rather than reporting itself. {@code Toolbox} turns anything thrown here
     * into a tool result the model reads, so a bad URL costs one iteration and the model can
     * correct it — which is exactly what a wrong link out of a document tree needs.
     */
    private static ToolOutput retrieve(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Fetch failed [" + response.statusCode() + "]: " + url);
            }

            return isImage(response) ? shown(url) : read(response);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException("Fetch failed: " + url, e);
        }
    }

    private static boolean isImage(HttpResponse<byte[]> response) {
        return response.headers()
                .firstValue("content-type")
                .map(type -> type.trim().toLowerCase().startsWith("image/"))
                .orElse(false);
    }

    /**
     * The bytes are dropped on purpose. The image travels as a URL the provider fetches for
     * itself, which keeps it re-readable on every later turn for the cost of a link, and keeps
     * the run's record legible — base64 would bury the request it appeared in.
     */
    private static ToolOutput shown(String url) {
        return new ToolOutput(
                "That URL is an image. It has been added to the conversation for you to look at.",
                new ImageRef(url));
    }

    /** UTF-8 named rather than defaulted — a platform charset mangles accented text silently. */
    private static ToolOutput read(HttpResponse<byte[]> response) {
        return ToolOutput.of(new String(response.body(), StandardCharsets.UTF_8));
    }
}
