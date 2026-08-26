package io.github.dbonkowska.dscribe.tool;

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
 * <p>The fixture tools are invented: {@code lookup} and {@code count} belong to no lesson.
 */
class ToolboxTest {

    record Lookup(String query, int limit) {}

    record Report(String label, int count) {}

    private static final String CALL_ID = "call_1";

    @Test
    void rejectsTwoToolsSharingAName() {
        List<Tool<?>> clashing = List.of(tool("lookup", args -> "a"), tool("lookup", args -> "b"));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new Toolbox(clashing));

        assertTrue(thrown.getMessage().contains("lookup"), thrown::getMessage);
    }

    @Test
    void passesAStringResultBackVerbatimAgainstTheCallItAnswers() {
        Message result = invoke(tool("lookup", args -> "found " + args.query()), "lookup",
                "{\"query\":\"x\",\"limit\":1}");

        assertEquals(Role.tool, result.role());
        assertEquals(CALL_ID, result.toolCallId());
        assertEquals("found x", result.content());
    }

    @Test
    void serialisesANonStringResultToJson() {
        Message result = invoke(tool("lookup", args -> new Report(args.query(), args.limit())),
                "lookup", "{\"query\":\"x\",\"limit\":2}");

        assertEquals("{\"label\":\"x\",\"count\":2}", result.content());
    }

    @Test
    void reportsArgumentsItCannotDeserialiseRatherThanThrowing() {
        Tool<Lookup> lookup = tool("lookup", args -> "never runs");

        assertToolFailure(invoke(lookup, "lookup", "{"), "truncated JSON");
        assertToolFailure(invoke(lookup, "lookup", "{\"query\":\"x\",\"limit\":\"lots\"}"), "wrong type");
    }

    @Test
    void reportsAHandlerFailureRatherThanThrowing() {
        Message result = invoke(
                tool("lookup", args -> { throw new RuntimeException("boom"); }),
                "lookup",
                "{\"query\":\"x\",\"limit\":1}");

        assertToolFailure(result, "handler threw");
        assertTrue(result.content().contains("boom"), result::content);
    }

    @Test
    void reportsAnUnknownToolRatherThanThrowing() {
        Message result = invoke(tool("lookup", args -> "a"), "nosuchtool", "{}");

        assertTrue(
                result.content().startsWith("Unknown tool: "),
                () -> "expected an unknown-tool report, got: " + result.content());
        assertTrue(result.content().contains("nosuchtool"), result::content);
    }

    @Test
    void specsListEveryToolInRegistrationOrder() {
        Toolbox toolbox = new Toolbox(List.of(tool("lookup", args -> "a"), tool("count", args -> "b")));

        List<String> names =
                toolbox.specs().stream().map(ToolSpec::function).map(FunctionSpec::name).toList();

        assertEquals(List.of("lookup", "count"), names);
    }

    private static Tool<Lookup> tool(String name, Function<Lookup, Object> handler) {
        return new Tool<>(name, "finds things", Lookup.class, handler);
    }

    private static Message invoke(Tool<?> registered, String calledName, String arguments) {
        return new Toolbox(List.of(registered))
                .invoke(new ToolCall(CALL_ID, "function", new ToolCall.Invocation(calledName, arguments)));
    }

    private static void assertToolFailure(Message result, String because) {
        assertEquals(CALL_ID, result.toolCallId());
        assertTrue(
                result.content().startsWith("Tool failed: "),
                () -> "expected a failure report (" + because + "), got: " + result.content());
    }
}