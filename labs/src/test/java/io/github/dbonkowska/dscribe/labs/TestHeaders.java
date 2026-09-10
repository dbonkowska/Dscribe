package io.github.dbonkowska.dscribe.labs;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Response headers for a test, without a server to send them.
 *
 * <p>{@link HttpHeaders} has no plain factory — the real one wants a map of lists and a filter —
 * and three test classes across two packages had written the same six lines. Public rather than
 * package-private for that reason: {@code labs.data} and {@code labs.hub} both need it.
 */
public final class TestHeaders {

    private TestHeaders() {}

    public static HttpHeaders of(String name, String value) {
        return of(Map.of(name, value));
    }

    public static HttpHeaders of(Map<String, String> values) {
        return HttpHeaders.of(
                values.entrySet().stream().collect(
                        Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue()))),
                (name, value) -> true);
    }
}
