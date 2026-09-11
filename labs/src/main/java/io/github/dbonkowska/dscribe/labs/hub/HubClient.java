package io.github.dbonkowska.dscribe.labs.hub;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class HubClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String hubApiKey;
    private final String hubBaseUrl;
    private final String verifyUrl;
    private final RunTranscript transcript;

    /** Records every call it makes, so the leg the model never sees is in the run's record too. */
    public HubClient(LabsConfig.Hub config, RunTranscript transcript) {
        this.hubApiKey = config.apiKey();
        this.hubBaseUrl = config.baseUrl();
        this.verifyUrl = config.verifyUrl();
        this.transcript = transcript;
    }

    /**
     * Downloads one of the hub's data files to {@code destination}, unless it is already there.
     *
     * <p>Where it lands is the caller's decision — {@code Artifacts} owns local layout. What
     * stays here is the URL, because it carries the API key.
     */
    public Path fetchData(String filename, Path destination) {
        return fetch(hubBaseUrl + "/data/" + hubApiKey + "/" + filename, destination);
    }

    /**
     * Reads one of the hub's data files and hands back its content, never touching the disk.
     *
     * <p>The counterpart to {@link #fetchData} rather than a replacement for it. That one exists
     * in order *not* to fetch twice, and every earlier lesson leans on the cache; this one is for
     * an input that changes underneath the run, where a cached read is silently right on the first
     * cycle and wrong on every one after. A {@code refresh} flag on the other would have put cache
     * invalidation into the method whose entire value is having none.
     *
     * <p>Recorded like any other hub exchange, and for the same reason the failed attempts are:
     * the content rotates, so a cycle that went wrong cannot be explained afterwards unless what
     * it read is in the file next to what it sent.
     */
    public String downloadData(String filename) {
        String url = hubBaseUrl + "/data/" + hubApiKey + "/" + filename;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // named rather than defaulted — a platform charset mangles accented text silently
            String body = new String(response.body(), StandardCharsets.UTF_8);

            // written before the status is looked at, like every other exchange recorded here
            transcript.hubCall(
                    "data · " + filename,
                    redacted(url),
                    response.statusCode(),
                    response.headers(),
                    "",
                    body);

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Data download failed [" + response.statusCode() + "]: " + redacted(url));
            }
            return body;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException("Data download failed: " + redacted(url), e);
        }
    }

    public Path fetch(String url, Path localPath) {
        if (Files.exists(localPath)) {
            return localPath;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Fetch failed [" + response.statusCode() + "]: " + redacted(url));
            }

            Files.createDirectories(localPath.getParent());
            Files.write(localPath, response.body());
            return localPath;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException("Fetch failed: " + redacted(url), e);
        }
    }

    /**
     * The data URL carries the API key in its path, and an exception message travels wherever its
     * stack trace does — a console, a CI log, a pasted snippet. {@link RunTranscript} redacts
     * everything it writes; nothing was doing the same for what this throws.
     */
    private String redacted(String url) {
        return url.replace(hubApiKey, "***");
    }

    /**
     * POSTs a record to one of the hub's API endpoints and returns the response body as it came.
     *
     * <p>The API key is merged into the JSON body, which is where the hub wants it, so callers
     * never handle it. The raw text goes back to whoever asked — for a tool handler that means
     * the model reads exactly what the hub said, including its error messages.
     */
    public String post(String label, String path, Object body) {
        try {
            ObjectNode json = mapper.valueToTree(body);
            json.put("apikey", hubApiKey);
            String sent = mapper.writeValueAsString(json);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(hubBaseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(sent, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            transcript.hubCall(
                    label, path, response.statusCode(), response.headers(), sent, response.body());
            return response.body();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * One attempt at the verify endpoint, returned whole — status, headers and body.
     *
     * <p>Does not throw on a non-2xx. An endpoint that fails on purpose makes the failed attempt
     * the interesting one: its status says whether to come back and its headers say when, and an
     * exception would discard both. Deciding what to do about that is {@link ResilientHub}'s job,
     * not this one's.
     *
     * <p>{@code label} names the attempt in the transcript, so a call that took four tries reads
     * as four entries rather than one mysterious pause.
     */
    public HubResponse send(String label, String taskName, Object answer) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "apikey", hubApiKey,
                    "task", taskName,
                    "answer", answer
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(verifyUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // before the status is looked at: a rejection is the exchange most worth keeping
            transcript.hubCall(
                    label, verifyUrl, response.statusCode(), response.headers(), json, response.body());

            return new HubResponse(response.statusCode(), response.headers(), response.body());
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Unchanged for its callers: one attempt, and the body it came back with. It delegates so that
     * how this project talks to the hub — the merged key, the recorded exchange — exists once.
     */
    public String verify(String taskName, Object answer) {
        return send("verify", taskName, answer).body();
    }
}