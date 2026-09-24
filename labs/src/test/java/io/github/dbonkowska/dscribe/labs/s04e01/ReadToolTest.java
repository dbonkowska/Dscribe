package io.github.dbonkowska.dscribe.labs.s04e01;

import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    private static final String PASSWORD = "pass-456";

    /** A record id in the panel's shape: 32 lowercase hex characters, invented. */
    private static final String HEX = "0123456789abcdef0123456789abcdef";

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
        tool("<html><body></body></html>").handler().apply(new ReadTool.Read("notes", ""));

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

        String result = (String) tool(page).handler().apply(new ReadTool.Read("front", "")).result();

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

        String result = (String) tool(page).handler().apply(new ReadTool.Read("front", "")).result();

        assertFalse(result.contains(KEY), result);
        assertEquals(2, result.split(Pattern.quote(ReadTool.REDACTED), -1).length - 1,
                () -> "each occurrence is marked, so the model knows something was there: " + result);
        assertTrue(result.contains("href=\"/r/ab12\""), "the rest of the attribute stays");
    }

    /**
     * Every credential, not only the one the panel is known to print: a users page is the one most
     * likely to echo an account's details, and a password there would go to the provider.
     */
    @Test
    void neverReturnsAnyOfTheSecrets() {
        String page = "<html><body><td>" + PASSWORD + "</td><td>" + KEY + "</td></body></html>";

        String result = (String) tool(page).handler().apply(new ReadTool.Read("front", "")).result();

        assertFalse(result.contains(PASSWORD), result);
        assertFalse(result.contains(KEY), result);
    }

    /** The schema narrows pages, but a provider is not obliged to honour a schema. */
    @Test
    void refusesAnUnknownPageWithoutFetchingAnything() {
        Tool<ReadTool.Read> tool = tool("<html><body></body></html>");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ReadTool.Read("elsewhere", "")));

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
                () -> tool.handler().apply(new ReadTool.Read("front", "")));

        assertTrue(thrown.getMessage().contains("login"), thrown::getMessage);
    }

    /**
     * A list page truncates each record. The one a run needed was cut off before its point, and the
     * model, unable to read it whole, overwrote it trying to. A record is read in full at
     * /{page}/{id}.
     */
    @Test
    void readsOneRecordByItsId() {
        tool("<html><body></body></html>").handler().apply(new ReadTool.Read("notes", HEX));

        assertEquals("/notes/" + HEX, fetched.getFirst().path());
        assertTrue(fetched.getFirst().label().contains(HEX), fetched.getFirst()::label);
    }

    /** The detail path follows the page's name, not its list path — the front page lists at "/". */
    @Test
    void readsARecordOnTheFrontPageUnderItsName() {
        tool("<html><body></body></html>").handler().apply(new ReadTool.Read("front", HEX));

        assertEquals("/front/" + HEX, fetched.getFirst().path());
    }

    /**
     * The id becomes part of the path, and the panel's own links include /delete/{id}: a GET there
     * may well delete. So an id is accepted only in the one shape a record id has, identified
     * positively, and anything else is refused before a request exists.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0",
            "0123456789ABCDEF0123456789ABCDEF",
            "../delete/0123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef/x",
            "0123456789abcdef0123456789abcdef?a=1"})
    void refusesAnIdThatIsNotARecordIdWithoutFetchingAnything(String id) {
        Tool<ReadTool.Read> tool = tool("<html><body></body></html>");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.handler().apply(new ReadTool.Read("notes", id)));

        assertEquals(List.of(), fetched);
        assertTrue(thrown.getMessage().contains("32"),
                () -> "the model has to be told what an id looks like: " + thrown.getMessage());
    }

    private Tool<ReadTool.Read> tool(String page) {
        ReadTool.Fetch fake = (label, path) -> {
            fetched.add(new Fetched(label, path));
            return page;
        };
        return new ReadTool(fake, pages(), List.of(KEY, PASSWORD)).tool("read", "reads one page");
    }
}
