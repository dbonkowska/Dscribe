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
                    .POST(HttpRequest.BodyPublishers.ofString(sent))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            transcript.hubCall(label, path, response.statusCode(), sent, response.body());
            return response.body();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String verify(String taskName, Object answer) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "apikey", hubApiKey,
                    "task", taskName,
                    "answer", answer
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(verifyUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            transcript.hubCall("verify", verifyUrl, response.statusCode(), json, response.body());
            return response.body();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}