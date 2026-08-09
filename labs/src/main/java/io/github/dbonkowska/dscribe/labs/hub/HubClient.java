package io.github.dbonkowska.dscribe.labs.hub;

import tools.jackson.databind.ObjectMapper;
import io.github.dbonkowska.dscribe.labs.config.LabsConfig;

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

    public HubClient(LabsConfig.Hub config) {
        this.hubApiKey = config.apiKey();
        this.hubBaseUrl = config.baseUrl();
        this.verifyUrl = config.verifyUrl();
    }

    public Path fetchFromHub(String episode, String filename) {
        Path localPath = Path.of("labs", "data", episode, filename);
        return fetch(hubBaseUrl + "/data/" + hubApiKey + "/" + filename, localPath);
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
                throw new RuntimeException("Fetch failed [" + response.statusCode() + "]: " + url);
            }

            Files.createDirectories(localPath.getParent());
            Files.write(localPath, response.body());
            return localPath;
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
            return response.body();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}