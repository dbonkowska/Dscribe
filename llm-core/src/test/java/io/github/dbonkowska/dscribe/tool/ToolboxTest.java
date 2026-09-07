package io.github.dbonkowska.dscribe.tool;

import io.github.dbonkowska.dscribe.conversation.ContentPart;
import io.github.dbonkowska.dscribe.conversation.Message;
import io.github.dbonkowska.dscribe.conversation.Role;
import io.github.dbonkowska.dscribe.conversation.ToolCall;
import io.github.dbonkowska.dscribe.llm.FunctionSpec;
import io.github.dbonkowska.dscribe.llm.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatch is the one place in the loop that must never throw. A model sending malformed
 * arguments, or a handler blowing up, has to come back as text the model can read and retry —
 * an exception here ends a multi-step run that was one corrected argument away from finishing.
 *
 * <p>An image is the other thing that cannot travel as a tool result: the provider's schema has
 * no place for one in a {@code role=tool} message, so it leaves here as a separate message the
 * agent appends afterwards. A failure path that produced one anyway would put a user message
 * between two tool results and invalidate the next request.
 *
 * <p>The fixture tools are invented: {@code lookup} and {@code count} belong to no lesson.
 */
class ToolboxTest {

    record Lookup(String query, int limit) {}

    record Report(String label, int count) {}

    private static final String CALL_ID = "call_1";

    private static final String ARGUMENTS = "{\"query\":\"x\",\"limit\":1}";

    private static final String IMAGE_URL = "https://e/x.png";

    @Test
    void rejectsTwoToolsSharingAName() {
        List<Tool<?>> clashing =
                List.of(tool("lookup", args -> ToolOutput.of("a")), tool("lookup", args -> ToolOutput.of("b")));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new Toolbox(clashing));

        assertTrue(thrown.getMessage().contains("lookup"), thrown::getMessage);
    }

    @Test
    void passesAStringResultBackVerbatimAgainstTheCallItAnswers() {
        Message result = invoke(tool("lookup", args -> ToolOutput.of("found " + args.query())),
                "lookup", ARGUMENTS).result();

        assertEquals(Role.tool, result.role());
        assertEquals(CALL_ID, result.toolCallId());
        assertEquals("found x", result.text());
    }

    @Test
    void serialisesANonStringResultToJson() {
        Message result = invoke(
                tool("lookup", args -> ToolOutput.of(new Report(args.query(), args.limit()))),
                "lookup", "{\"query\":\"x\",\"limit\":2}").result();

        assertEquals("{\"label\":\"x\",\"count\":2}", result.text());
    }

    @Test
    void sendsAnImageAsAUserMessageAlongsideTheToolResult() {
        ToolCallMessages messages = invoke(
                tool("lookup", args -> new ToolOutput("fetched an image", new ImageRef(IMAGE_URL))),
                "lookup", ARGUMENTS);

        assertEquals(CALL_ID, messages.result().toolCallId());
        assertEquals("fetched an image", messages.result().text());

        assertEquals(1, messages.attachments().size(),
                () -> "expected exactly one attachment, got: " + messages.attachments());

        Message attachment = messages.attachments().getFirst();
        assertEquals(Role.user, attachment.role(), "a tool-role message cannot carry an image");

        List<ContentPart> parts = parts(attachment);
        assertEquals(2, parts.size(), () -> "expected a text part and an image part, got: " + parts);
        assertEquals("text", parts.get(0).type());
        assertTrue(
                parts.get(0).text().contains(IMAGE_URL),
                () -> "the text part names the source, which is the model's only handle on the "
                        + "file it is being shown: " + parts.get(0));
        assertEquals("image_url", parts.get(1).type());
        assertEquals(IMAGE_URL, parts.get(1).imageUrl().url());
    }

    @Test
    void attachesNothingWhereTheHandlerProducedNoImage() {
        Tool<Lookup> lookup = tool("lookup", args -> ToolOutput.of("a"));
        Tool<Lookup> exploding = tool("lookup", args -> { throw new RuntimeException("boom"); });

        assertEquals(List.of(), invoke(lookup, "lookup", ARGUMENTS).attachments(), "a plain result");
        assertEquals(List.of(), invoke(lookup, "nosuchtool", "{}").attachments(), "an unknown tool");
        assertEquals(List.of(), invoke(lookup, "lookup", "{").attachments(), "unreadable arguments");
        assertEquals(List.of(), invoke(exploding, "lookup", ARGUMENTS).attachments(), "a failed handler");
    }

    @Test
    void reportsArgumentsItCannotDeserialiseRatherThanThrowing() {
        Tool<Lookup> lookup = tool("lookup", args -> ToolOutput.of("never runs"));

        assertToolFailure(invoke(lookup, "lookup", "{").result(), "truncated JSON");
        assertToolFailure(
                invoke(lookup, "lookup", "{\"query\":\"x\",\"limit\":\"lots\"}").result(), "wrong type");
    }

    @Test
    void reportsAHandlerFailureRatherThanThrowing() {
        Message result = invoke(
                tool("lookup", args -> { throw new RuntimeException("boom"); }),
                "lookup",
                ARGUMENTS).result();

        assertToolFailure(result, "handler threw");
        assertTrue(result.text().contains("boom"), result::text);
    }

    @Test
    void reportsAnUnknownToolRatherThanThrowing() {
        Message result = invoke(tool("lookup", args -> ToolOutput.of("a")), "nosuchtool", "{}").result();

        assertTrue(
                result.text().startsWith("Unknown tool: "),
                () -> "expected an unknown-tool report, got: " + result.text());
        assertTrue(result.text().contains("nosuchtool"), result::text);
    }

    @Test
    void specsListEveryToolInRegistrationOrder() {
        Toolbox toolbox = new Toolbox(
                List.of(tool("lookup", args -> ToolOutput.of("a")), tool("count", args -> ToolOutput.of("b"))));

        List<String> names =
                toolbox.specs().stream().map(ToolSpec::function).map(FunctionSpec::name).toList();

        assertEquals(List.of("lookup", "count"), names);
    }

    private static Tool<Lookup> tool(String name, Function<Lookup, ToolOutput> handler) {
        return new Tool<>(name, "finds things", Lookup.class, handler);
    }

    private static ToolCallMessages invoke(Tool<?> registered, String calledName, String arguments) {
        return new Toolbox(List.of(registered))
                .invoke(new ToolCall(CALL_ID, "function", new ToolCall.Invocation(calledName, arguments)));
    }

    @SuppressWarnings("unchecked")
    private static List<ContentPart> parts(Message message) {
        return (List<ContentPart>) message.content();
    }

    private static void assertToolFailure(Message result, String because) {
        assertEquals(CALL_ID, result.toolCallId());
        assertTrue(
                result.text().startsWith("Tool failed: "),
                () -> "expected a failure report (" + because + "), got: " + result.text());
    }
}
