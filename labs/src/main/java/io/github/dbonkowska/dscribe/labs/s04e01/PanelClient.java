package io.github.dbonkowska.dscribe.labs.s04e01;

import io.github.dbonkowska.dscribe.labs.data.RunTranscript;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A logged-in session on the operator panel, used only to read. It logs in on the first read and
 * keeps the session cookie for the rest of the run; nothing here posts anything but the login.
 *
 * <p>Every exchange, the login included, is recorded before its status is looked at, so reads sit in
 * the transcript beside the writes they led to. The login carries the password and the hub key; the
 * transcript redacts both by value.
 *
 * <p>Whether the login worked is not decided here. A failed login answers with the login form rather
 * than an error status, so {@link ReadTool} looks for the form in the page it was about to return.
 */
final class PanelClient implements ReadTool.Fetch {

    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String baseUrl;
    private final Map<String, String> loginForm;
    private final RunTranscript transcript;
    private boolean loggedIn;

    /**
     * @param baseUrl   the panel's origin, without a trailing slash
     * @param accessKey the hub key, which the panel takes as its access key
     */
    PanelClient(String baseUrl, String login, String password, String accessKey, RunTranscript transcript) {
        this.baseUrl = baseUrl;
        this.transcript = transcript;
        this.loginForm = new LinkedHashMap<>();
        loginForm.put("action", "login");
        loginForm.put("login", login);
        loginForm.put("password", password);
        loginForm.put("access_key", accessKey);
    }

    @Override
    public String get(String label, String path) {
        if (!loggedIn) {
            login();
            loggedIn = true;
        }
        return exchange(label, path, HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(), "");
    }

    private void login() {
        String form = loginForm.entrySet().stream()
                .map(field -> encode(field.getKey()) + "=" + encode(field.getValue()))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        exchange("panel login", "/", request, form);
    }

    private String exchange(String label, String path, HttpRequest request, String requestBody) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // before the status is looked at: a failed read is the exchange most worth keeping
            transcript.hubCall(label, baseUrl + path, response.statusCode(), response.headers(),
                    requestBody, response.body());
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
