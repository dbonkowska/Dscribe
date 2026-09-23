package io.github.dbonkowska.dscribe.labs.s03e05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry is what stands between a name the model writes and the address the hub key is sent
 * to, so each refusal here is a request that would otherwise have gone out.
 *
 * <p>Replies follow the shape a real search returned; the names, paths and descriptions are
 * invented and belong to no lesson.
 */
class ToolRegistryTest {

    private final ToolRegistry registry = new ToolRegistry();

    @Test
    void registersEveryToolAReplyNames() {
        List<String> refusals = registry.register(reply(
                tool("alpha", "/api/alpha", "query"),
                tool("beta", "/api/beta", "query")));

        assertEquals(List.of(), refusals);
        assertEquals(Optional.of("/api/alpha"), registry.pathOf("alpha"));
        assertEquals(Optional.of("/api/beta"), registry.pathOf("beta"));
        // two, not three: a search returns at most three, and fewer is ordinary
        assertEquals(List.of("alpha", "beta"), registry.known());
    }

    @Test
    void knowsNothingBeforeASearch() {
        assertEquals(Optional.empty(), registry.pathOf("gamma"));
        assertEquals(List.of(), registry.known());
    }

    /** Only a plain path keeps the key on the hub — see {@link HubPath}. */
    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/x", "//evil.example/x", "@evil.example/x"})
    void refusesAToolThatIsNotAPlainPathOnTheHub(String url) {
        List<String> refusals = registry.register(reply(tool("alpha", url, "query")));

        assertEquals(Optional.empty(), registry.pathOf("alpha"));
        assertEquals(1, refusals.size());
        assertTrue(refusals.getFirst().contains("alpha"), refusals::toString);
        assertTrue(refusals.getFirst().contains(url), refusals::toString);
    }

    /** Every call sends {@code query}; a tool that names another argument would receive nothing it reads. */
    @Test
    void refusesAToolThatTakesADifferentParameter() {
        List<String> refusals = registry.register(reply(tool("alpha", "/api/alpha", "q")));

        assertEquals(Optional.empty(), registry.pathOf("alpha"));
        assertEquals(1, refusals.size());
        assertTrue(refusals.getFirst().contains("alpha"), refusals::toString);
        assertTrue(refusals.getFirst().contains("\"q\""), refusals::toString);
    }

    @Test
    void refusesAToolWithNoName() {
        List<String> refusals = registry.register(reply(tool("", "/api/alpha", "query")));

        assertEquals(List.of(), registry.known());
        assertEquals(1, refusals.size());
        assertTrue(refusals.getFirst().contains("/api/alpha"), refusals::toString);
    }

    /** Overlapping searches return the same tool again, and that is not a conflict. */
    @Test
    void acceptsTheSameToolFoundTwice() {
        registry.register(reply(tool("alpha", "/api/alpha", "query")));
        List<String> refusals = registry.register(reply(tool("alpha", "/api/alpha", "query")));

        assertEquals(List.of(), refusals);
        assertEquals(List.of("alpha"), registry.known());
    }

    /** Overwriting would silently re-point a name the model already used. */
    @Test
    void keepsTheFirstAddressWhenANameMoves() {
        registry.register(reply(tool("alpha", "/api/alpha", "query")));
        List<String> refusals = registry.register(reply(tool("alpha", "/api/other", "query")));

        assertEquals(Optional.of("/api/alpha"), registry.pathOf("alpha"));
        assertEquals(1, refusals.size());
        assertTrue(refusals.getFirst().contains("/api/alpha"), refusals::toString);
        assertTrue(refusals.getFirst().contains("/api/other"), refusals::toString);
    }

    /**
     * Nothing here knows what a miss or an error looks like, so a reply without tools registers
     * nothing and says nothing: the model reads the reply itself.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":1,\"message\":\"nothing\"}",
            "{\"code\":1,\"tools\":[]}",
            "{\"tools\":\"not a list\"}",
            "not json at all"})
    void registersNothingFromAReplyWithoutTools(String reply) {
        List<String> refusals = registry.register(reply);

        assertEquals(List.of(), refusals);
        assertEquals(List.of(), registry.known());
    }

    private static String reply(String... tools) {
        return "{\"code\":1,\"message\":\"found\",\"query\":\"x\",\"tools\":["
                + String.join(",", tools) + "]}";
    }

    private static String tool(String name, String url, String parameter) {
        return "{\"name\":\"" + name + "\",\"url\":\"" + url + "\",\"description\":\"d\","
                + "\"parameter\":\"" + parameter + "\",\"score\":1,\"matched_keywords\":[\"k\"]}";
    }
}
