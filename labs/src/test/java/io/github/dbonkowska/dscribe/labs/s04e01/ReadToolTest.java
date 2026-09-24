package io.github.dbonkowska.dscribe.labs.s04e01;

import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model's only view of the records it has to edit. What makes it worth testing is what it
 * leaves out: the panel prints the hub key on every logged-in page, and anything this returns goes
 * to the model and from there to the provider. A key that slipped through would look like any other
 * attribute in the markup, and nothing downstream would notice.
 *
 * <p>Pages, paths, markup and the key are invented and belong to no lesson.
 */
class ReadToolTest {

    private static final String KEY = "KEY-123";

    /** One fetch the fake panel received. */
    private record Fetched(String label, String path) {}

    /** What the fake panel was asked for, in order. */
    private final List<Fetched> fetched = new ArrayList<>();

    private static Map<String, String> pages() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("front", "/");
        pages.put("notes", "/notes");
        return pages;
    }

    /** The narrowing. A page the bundle does not name is not one the model can ask for. */
    @Test
    void offersOnlyTheConfiguredPagesInOrder() {
        List<String> offered = new ArrayList<>();
        tool("<html><body></body></html>").spec().function().parameters()
                .at("/properties/page/enum")
                .forEach(node -> offered.add(node.stringValue()));

        assertEquals(List.of("front", "notes"), offered);
    }

    /** The model names a page; the path it goes to is the bundle's, not the model's. */
    @Test
    void fetchesThePathTheBundleGivesThePage() {
        tool("<html><body></body></html>").handler().apply(new ReadTool.Read("notes"));

        assertEquals(1, fetched.size());
        assertEquals("/notes", fetched.getFirst().path());
        assertTrue(fetched.getFirst().label().contains("notes"),
                () -> "the transcript has to read per page: " + fetched.getFirst().label());
    }

    /**
     * Markup is kept because the ids live in it. What goes is what the model cannot use and pays
     * for on every later turn: the head, the styles and the scripts.
     */
    @Test
    void keepsTheBodysMarkupAndDropsHeadStylesAndScripts() {
        String page = "<html><head><title>t</title><style>x{}</style></head>"
                + "<body><a href=\"/r/ab12\">Row</a><style>y{}</style><script>s()</script></body></html>";

        String result = (String) tool(page).handler().apply(new ReadTool.Read("front")).result();

        assertTrue(result.contains("<a href=\"/r/ab12\">Row</a>"), result);
        assertFalse(result.contains("<title"), result);
        assertFalse(result.contains("<style"), result);
        assertFalse(result.contains("<script"), result);
    }

    /** Twice, and once inside an attribute: a check that stops at the first would pass one of them. */
    @Test
    void neverReturnsTheKey() {
        String page = "<html><body><span class=\"chip\">" + KEY + "</span>"
                + "<a data-k=\"" + KEY + "\" href=\"/r/ab12\">Row</a></body></html>";

        String result = (String) tool(page).handler().apply(new ReadTool.Read("front")).result();

        assertFalse(result.contains(KEY), result);
        assertEquals(2, result.split(Pattern.quote(ReadTool.REDACTED), -1).length - 1,
                () -> "each occurrence is marked, so the model knows something was there: " + result);
        assertTrue(result.contains("href=\"/r/ab12\""), "the rest of the attribute stays");
    }

    /** The schema narrows pages, but a provider is not obliged to honour a schema. */
    @Test
    void refusesAnUnknownPageWithoutFetchingAnything() {
        Tool<ReadTool.Read> tool = tool("<html><body></body></html>");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ReadTool.Read("elsewhere")));

        assertEquals(List.of(), fetched);
        assertTrue(thrown.getMessage().contains("front") && thrown.getMessage().contains("notes"),
                () -> "the model has to be told what it may read: " + thrown.getMessage());
    }

    /**
     * A failed login answers with the login form rather than an error. Handed over as a page, it
     * reads as an empty panel, and the model would conclude there is nothing to edit.
     */
    @Test
    void refusesALoginFormInsteadOfReturningIt() {
        String page = "<html><body><form method=\"post\"><input name=\"access_key\"></form></body></html>";
        Tool<ReadTool.Read> tool = tool(page);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> tool.handler().apply(new ReadTool.Read("front")));

        assertTrue(thrown.getMessage().contains("login"), thrown::getMessage);
    }

    private Tool<ReadTool.Read> tool(String page) {
        ReadTool.Fetch fake = (label, path) -> {
            fetched.add(new Fetched(label, path));
            return page;
        };
        return new ReadTool(fake, pages(), KEY).tool("read", "reads one page");
    }
}
