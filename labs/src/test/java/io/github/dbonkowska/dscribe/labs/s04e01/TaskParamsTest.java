package io.github.dbonkowska.dscribe.labs.s04e01;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys whose mistakes produce a run that cannot succeed. A string key nobody wrote binds to null
 * rather than failing, and would fail later and further from the file: a missing task name on the
 * first action, a missing flag pattern after the result was already earned.
 *
 * <p>The panel keys matter more than most: the hub key is sent to the panel as its access key, so
 * where the panel is and which paths are read decide where that key goes.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class TaskParamsTest {

    /** Every key a run needs, with a value — each test below takes one of them away. */
    private static final String COMPLETE = """
            verifyTask=x-task
            flagPattern=[{]F[}]
            action.name=act
            action.description=does one action
            panelBaseUrl=https://panel.example
            login=user
            password=secret
            pages.front=/
            pages.notes=/notes
            read.name=read
            read.description=reads one page
            writablePages.1=front
            """;

    @Test
    void bindsACompleteFile() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals("x-task", params.verifyTask());
        assertEquals("[{]F[}]", params.flagPattern());
        assertEquals("act", params.action().name());
        assertEquals("does one action", params.action().description());
        assertEquals("https://panel.example", params.panelBaseUrl());
        assertEquals("user", params.login());
        assertEquals("secret", params.password());
        assertEquals("read", params.read().name());
        assertEquals("reads one page", params.read().description());
    }

    /**
     * The binder does not keep the file's order — a real bundle of four pages came back shuffled,
     * where two pages had happened to survive. So the order the schema's enum offers is fixed here
     * instead, by name. These four bind as west, south, east, north — neither the file's order nor
     * sorted — so a map left as bound cannot pass by luck, as a first fixture here did.
     */
    @Test
    void bindsThePagesSortedByName() {
        String props = COMPLETE
                .replace("pages.front=/\n", "")
                .replace("pages.notes=/notes\n", "pages.north=/n\npages.east=/e\npages.south=/s\npages.west=/w\n")
                .replace("writablePages.1=front", "writablePages.1=east");

        TaskParams params = new JavaPropsMapper().readValue(props, TaskParams.class);

        assertEquals(List.of("east", "north", "south", "west"), List.copyOf(params.pages().keySet()));
        assertEquals(List.of("/e", "/n", "/s", "/w"), List.copyOf(params.pages().values()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "action.name", "action.description",
            "panelBaseUrl", "login", "password", "read.name", "read.description"})
    void refusesARequiredKeyThatWasNeverWritten(String key) {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith(key + "="))
                .collect(Collectors.joining("\n"));

        // Jackson wraps anything a record constructor throws, so the type on this path is its
        // wrapper rather than ours. The binding fails and the message names the key to edit.
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key),
                () -> "it has to name the key to edit: " + thrown.getMessage());
    }

    /** Present but empty is the same mistake as absent, and has to be refused the same way. */
    @ParameterizedTest
    @ValueSource(strings = {
            "verifyTask", "flagPattern", "action.name", "action.description",
            "panelBaseUrl", "login", "password", "read.name", "read.description"})
    void refusesARequiredKeyLeftBlank(String key) {
        String props = COMPLETE.lines()
                .map(line -> line.startsWith(key + "=") ? key + "=   " : line)
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(key), thrown::getMessage);
    }

    /**
     * A pattern that does not compile throws on the first reply it is applied to, after the run has
     * already spent and the hub has printed the result it was meant to find.
     */
    @Test
    void refusesAFlagPatternThatDoesNotCompile() {
        String props = COMPLETE.replace("flagPattern=[{]F[}]", "flagPattern=[a-z");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("flagPattern"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("backslash"),
                () -> "it has to say how properties files mangle patterns: " + thrown.getMessage());
    }

    /** An empty enum is a read tool that can read nothing, and the model would learn no id. */
    @Test
    void refusesAFileWithNoPages() {
        String props = COMPLETE.lines()
                .filter(line -> !line.startsWith("pages."))
                .collect(Collectors.joining("\n"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("pages"), thrown::getMessage);
    }

    /**
     * Each path is appended to the panel's base URL, and the request carries the hub key. Without a
     * single leading slash, a path can move the request to another host.
     */
    @ParameterizedTest
    @ValueSource(strings = {"notes", "//evil.example/x", "https://evil.example/", "@evil.example/x", "/a b"})
    void refusesAPagePathThatIsNotAPlainPath(String path) {
        String props = COMPLETE.replace("pages.notes=/notes", "pages.notes=" + path);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("notes"),
                () -> "it has to name the page to fix: " + thrown.getMessage());
    }

    /**
     * The login form carries the hub key, so the base has to be encrypted, and has to be only an
     * origin — a path on it would be one the page paths could not see and the check above did not
     * cover.
     */
    @ParameterizedTest
    @ValueSource(strings = {"http://panel.example", "https://panel.example/x", "panel.example", "https://"})
    void refusesABaseUrlThatIsNotAnHttpsOrigin(String base) {
        String props = COMPLETE.replace("panelBaseUrl=https://panel.example", "panelBaseUrl=" + base);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("panelBaseUrl"), thrown::getMessage);
    }

    @Test
    void bindsTheWritablePages() {
        TaskParams params = new JavaPropsMapper().readValue(COMPLETE, TaskParams.class);

        assertEquals(List.of("front"), params.writablePages());
    }

    /** No writable page is a run that can change nothing, and would spend a model call learning it. */
    @Test
    void refusesAFileWithNoWritablePages() {
        String props = COMPLETE.replace("writablePages.1=front\n", "");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("writablePages"), thrown::getMessage);
    }

    /**
     * A writable page the panel does not show is one the model can write and never read back — and a
     * typo here would refuse every write to the page that was meant.
     */
    @Test
    void refusesAWritablePageThatIsNotAConfiguredPage() {
        String props = COMPLETE.replace("writablePages.1=front", "writablePages.1=frnt");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains("frnt"), thrown::getMessage);
    }

    /** Page names now form a path segment of a detail read, so they have to be one. */
    @ParameterizedTest
    @ValueSource(strings = {"a/b", "A", "a?b", "a%2f", "a;b"})
    void refusesAPageNameThatIsNotAPlainSegment(String name) {
        String props = COMPLETE.replace("pages.notes=/notes", "pages." + name + "=/notes");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new JavaPropsMapper().readValue(props, TaskParams.class));

        assertTrue(thrown.getMessage().contains(name), thrown::getMessage);
    }

    /** A trailing slash is still an origin, and the one most likely to be written. */
    @Test
    void acceptsABaseUrlWithATrailingSlash() {
        String props = COMPLETE.replace("panelBaseUrl=https://panel.example", "panelBaseUrl=https://panel.example/");

        TaskParams params = new JavaPropsMapper().readValue(props, TaskParams.class);

        assertEquals("https://panel.example", params.panelBaseUrl(), "stored without it, so base + path has one slash");
    }
}
