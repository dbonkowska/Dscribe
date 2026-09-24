package io.github.dbonkowska.dscribe.labs.s04e01;

import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The read half of a run whose write half returns no records. The endpoint that edits takes a
 * record's id and hands none out; the operator panel shows them. So the model names a page, this
 * fetches it on a logged-in session, and hands back the markup.
 *
 * <p>The page is a name from a closed set, never a path: code supplies the address, and the hub key
 * — the panel's access key — goes nowhere the bundle did not name. The set is narrowed as a schema
 * {@code enum} and checked again here, because a provider is not obliged to honour a schema.
 *
 * <p>Markup, not text. The ids sit in attributes, and converting to text would drop them. What goes
 * is what the model cannot use and would pay for on every later turn: the head, styles and scripts.
 *
 * <p>The panel prints the hub key on every logged-in page. Whatever this returns goes to the model
 * and from there to the provider, so the key is replaced by value before it leaves — the same way
 * the transcript redacts, and for the same reason: it does not matter where on the page it sits.
 */
final class ReadTool {

    /** What stands where the key was, so the model sees that something was removed. */
    static final String REDACTED = "[redacted]";

    /**
     * The whole schema the model sees.
     *
     * @param page one of the pages the bundle names
     */
    record Read(String page) {}

    /**
     * One GET on the logged-in panel, returning the body as it came — {@code PanelClient::get}, and a
     * recording lambda in tests.
     */
    @FunctionalInterface
    interface Fetch {
        String get(String label, String path);
    }

    private static final Pattern BODY = Pattern.compile("<body[^>]*>(.*)</body>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NOISE = Pattern.compile(
            "<head[^>]*>.*?</head>|<style[^>]*>.*?</style>|<script[^>]*>.*?</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * The login form's own field, present only when the session is not logged in. A failed login
     * answers with the form rather than an error, so this is the one sign of it.
     */
    private static final String LOGIN_FIELD = "name=\"access_key\"";

    private final Fetch fetch;
    private final Map<String, String> pages;
    private final String secret;

    /**
     * @param pages  page name to a path on the panel, in the order the model is offered them
     * @param secret the hub key, removed from every page before it is returned
     */
    ReadTool(Fetch fetch, Map<String, String> pages, String secret) {
        this.fetch = fetch;
        this.pages = pages;
        this.secret = secret;
    }

    /** Name and description come from the lesson bundle: they are prompt surface. */
    Tool<Read> tool(String name, String description) {
        ObjectNode schema = SchemaUtils.from(Read.class);
        ArrayNode allowed = SchemaUtils.at(schema, "/properties/page").putArray("enum");
        for (String page : pages.keySet()) {
            allowed.add(page);
        }

        return new Tool<>(name, description, Read.class, args -> read(args.page()), schema);
    }

    /**
     * Each refusal throws. {@code Toolbox} turns that into a tool result the model reads, and both
     * say what to do instead.
     */
    private ToolOutput read(String page) {
        String path = pages.get(page);
        if (path == null) {
            throw new IllegalArgumentException(
                    "No page " + page + ". Nothing was read. Read one of " + pages.keySet() + ".");
        }

        String html = fetch.get("read · " + page, path);
        if (html.contains(LOGIN_FIELD)) {
            throw new IllegalStateException(
                    "The panel answered with its login form, so the login failed and " + page
                            + " was not read. Reading again will not help; the run's panel login needs fixing.");
        }

        return ToolOutput.of(redact(strip(html)));
    }

    private static String strip(String html) {
        Matcher body = BODY.matcher(html);
        String inner = body.find() ? body.group(1) : html;
        return NOISE.matcher(inner).replaceAll("").strip();
    }

    /** A blank secret is not replaced: it would match between every character. */
    private String redact(String page) {
        return secret == null || secret.isBlank() ? page : page.replace(secret, REDACTED);
    }
}
