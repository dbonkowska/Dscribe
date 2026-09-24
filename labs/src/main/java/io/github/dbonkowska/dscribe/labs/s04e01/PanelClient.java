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
 * A logged-in session on the operator panel, used only to read. The runner logs in once, before the
 * agent loop, and the session cookie serves every read after; nothing here posts anything but the
 * login.
 *
 * <p>The login is also the exercise's reset: a panel login clears whatever an earlier run changed.
 * That is why it is eager and explicit rather than done on the first read — it has to land before
 * any write, and it has to read as the reset it is, so nobody moves or caches it as an optimisation.
 *
 * <p>Every exchange, the login included, is recorded before its status is looked at, so reads sit in
 * the transcript beside the writes they led to. The login carries the password and the hub key; the
 * transcript redacts both by value.
 *
 * <p>A failed session shows in two ways, and each is caught where it shows. A read answered with
 * anything but 2xx — a redirect to the login, a missing record — is refused here, on its status. A
 * login that failed answers 200 with the login form, which only the body reveals, so
 * {@link ReadTool} looks for the form in the page it was about to return.
 */
final class PanelClient implements ReadTool.Fetch {

    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String baseUrl;
    private final Map<String, String> loginForm;
    private final RunTranscript transcript;

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

    /**
     * Refuses anything but 2xx, as a tool result the model reads. With redirects not followed, an
     * expired session arrives as a near-empty body, and handed over as a page it would read as a
     * panel with nothing on it — the misreading the login-form check exists to prevent.
     */
    @Override
    public String get(String label, String path) {
        HttpResponse<String> response =
                exchange(label, path, HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(), "");
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            String location = response.headers().firstValue("Location").map(to -> ", redirecting to " + to).orElse("");
            throw new IllegalStateException(
                    "The panel answered " + path + " with status " + status + location + ", so nothing was"
                            + " read. A redirect usually means the session was lost; a 404, that no record"
                            + " has that id on that page.");
        }
        return response.body();
    }

    /**
     * Logs in and, in doing so, resets the exercise. Called once by the runner before the agent loop.
     * The response is not checked here: a failed login answers 200 with the login form, which the
     * first read refuses.
     */
    void login() {
        String form = loginForm.entrySet().stream()
                .map(field -> encode(field.getKey()) + "=" + encode(field.getValue()))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        exchange("panel login", "/", request, form);
    }

    private HttpResponse<String> exchange(String label, String path, HttpRequest request, String requestBody) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // before the status is looked at: a failed read is the exchange most worth keeping
            transcript.hubCall(label, baseUrl + path, response.statusCode(), response.headers(),
                    requestBody, response.body());
            return response;
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
