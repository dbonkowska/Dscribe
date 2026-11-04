package io.github.dbonkowska.dscribe.labs.s05e03;

import io.github.dbonkowska.dscribe.labs.TestHeaders;
import io.github.dbonkowska.dscribe.labs.data.RunTranscript;
import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import io.github.dbonkowska.dscribe.labs.hub.HubSend;
import io.github.dbonkowska.dscribe.labs.hub.RateLimitHeaders;
import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.hub.RetryPolicy;
import io.github.dbonkowska.dscribe.labs.s05e03.TaskParams.Field;
import io.github.dbonkowska.dscribe.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model reports what it read; code moves the date, writes every value the way the hub compares
 * it, and prints the answer on the server. A wrong shift or a value spelled differently is rejected as
 * a wrong answer about the content, with nothing to say the slip was arithmetic or formatting — so
 * those are pinned here, at the boundaries where they slip.
 *
 * <p>Every "nothing was sent" assertion sits after the call it is about. Field names, values and
 * replies are invented.
 */
class ReportToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<Field> FIELDS = List.of(
            new Field("when", "date"), new Field("town", "text"), new Field("x", "decimal"), new Field("y", "decimal"));

    /** The fixed head of every command; what follows it is the answer, single-quoted. */
    private static final String PRINTF = "printf '%s\\n' ";

    /** The command strings the fake hub was handed, in order. */
    private final List<String> sent = new ArrayList<>();

    private int status = 200;
    private String reply = "{\"code\":0,\"message\":\"ok\"}";

    @Test
    void printsTheShiftedAnswerInFieldOrderWithNumbersAsNumbers(@TempDir Path root) {
        report(root, -1, "2024-03-01", "Ab", "10.000001", "-5,5");

        assertEquals(List.of(PRINTF + "'{\"when\":\"2024-02-29\",\"town\":\"Ab\",\"x\":10.000001,\"y\":-5.5}'"), sent);
    }

    /** Month and year boundaries are exactly where subtraction slips, and the leap day sits on one. */
    @ParameterizedTest
    @CsvSource({"-1, 2024-03-01, 2024-02-29", "-1, 2025-01-01, 2024-12-31", "2, 2024-02-28, 2024-03-01"})
    void movesTheDateByTheBundlesShift(int shift, String found, String expected, @TempDir Path root) {
        report(root, shift, found, "Ab", "1", "2");

        assertEquals(expected, answer().get("when").asString());
    }

    @ParameterizedTest
    @CsvSource({"2020-02-30", "01.03.2024", "2024-3-1", "yesterday"})
    void refusesADateThatIsNotARealIsoDate(String found, @TempDir Path root) {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> report(root, -1, found, "Ab", "1", "2"));

        assertTrue(thrown.getMessage().contains("when") && thrown.getMessage().contains(found), thrown.getMessage());
        assertTrue(sent.isEmpty(), () -> "sent: " + sent);
    }

    @Test
    void refusesADecimalItCannotRead(@TempDir Path root) {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> report(root, -1, "2024-03-01", "Ab", "about 10", "2"));

        assertTrue(thrown.getMessage().contains("x") && thrown.getMessage().contains("about 10"), thrown.getMessage());
        assertTrue(sent.isEmpty(), () -> "sent: " + sent);
    }

    /** A provider that ignores the schema can leave a key out; that is a refusal naming it, not a null sent. */
    @Test
    void refusesAReportMissingAField(@TempDir Path root) {
        Map<String, Object> values = new LinkedHashMap<>(Map.of("when", "2024-03-01", "town", "Ab", "x", "1"));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> tool(root, -1).handler().apply(values));

        assertTrue(thrown.getMessage().contains("y"), thrown.getMessage());
        assertTrue(sent.isEmpty(), () -> "sent: " + sent);
    }

    /** A number sent despite the schema has already been through a double, so its precision can't be trusted. */
    @Test
    void refusesAValueThatIsNotText(@TempDir Path root) {
        Map<String, Object> values = new LinkedHashMap<>(Map.of("when", "2024-03-01", "town", "Ab", "x", 10.5, "y", "2"));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> tool(root, -1).handler().apply(values));

        assertTrue(thrown.getMessage().contains("x") && thrown.getMessage().contains("string"), thrown.getMessage());
        assertTrue(sent.isEmpty(), () -> "sent: " + sent);
    }

    /** The precision is the source's; rescaling it, or writing an exponent, changes the value the hub compares. */
    @Test
    void writesADecimalExactlyAsGiven(@TempDir Path root) {
        report(root, -1, "2024-03-01", "Ab", "10.000001000", "52.1");

        String json = unquoted(sent.getFirst());
        assertTrue(json.contains("\"x\":10.000001000"), json);
        assertTrue(json.contains("\"y\":52.1"), json);
    }

    /** A small value is where a {@code BigDecimal} switches to an exponent on its own. */
    @Test
    void writesASmallDecimalWithoutAnExponent(@TempDir Path root) {
        report(root, -1, "2024-03-01", "Ab", "0.0000001", "1");

        String json = unquoted(sent.getFirst());
        assertTrue(json.contains("\"x\":0.0000001"), json);
    }

    /** A quote in a value would end the shell's quoting early and turn the rest into a command. */
    @Test
    void keepsASingleQuoteInsideTheShellQuoting(@TempDir Path root) {
        report(root, -1, "2024-03-01", "O'Brien", "1", "2");

        assertTrue(sent.getFirst().contains("O'\\''Brien"), sent.getFirst());
        assertEquals("O'Brien", answer().get("town").asString());
    }

    /** The hub compares the name as text; an escaped form is a different string on the other side of a printf. */
    @Test
    void sendsANonAsciiNameAsItsCharacters(@TempDir Path root) {
        report(root, -1, "2024-03-01", "Łódź", "1", "2");

        assertTrue(sent.getFirst().contains("Łódź"), sent.getFirst());
    }

    /** A wrong answer names its field, which is the model's to act on: returned, not thrown. */
    @Test
    void handsBackTheHubsVerdictWordForWord(@TempDir Path root) {
        status = 400;
        reply = "{\"code\":-840,\"message\":\"Invalid value in field \\\"when\\\".\"}";

        Object result = report(root, -1, "2024-03-01", "Ab", "1", "2");

        assertEquals(reply, result);
    }

    @Test
    void asksForEveryFieldAsARequiredString(@TempDir Path root) {
        JsonNode parameters = tool(root, -1).spec().function().parameters();

        assertEquals(List.of("when", "town", "x", "y"), parameters.get("properties").propertyNames().stream().toList());
        assertEquals(MAPPER.readTree("[\"when\",\"town\",\"x\",\"y\"]"), parameters.get("required"));
        assertEquals(false, parameters.get("additionalProperties").asBoolean());
        for (JsonNode property : parameters.get("properties")) {
            assertEquals("string", property.get("type").asString());
        }
    }

    private Object report(Path root, int shift, String when, String town, String x, String y) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("when", when);
        values.put("town", town);
        values.put("x", x);
        values.put("y", y);
        return tool(root, shift).handler().apply(values).result();
    }

    /** The answer the last command would print, read back the way the shell would unquote it. */
    private JsonNode answer() {
        return MAPPER.readTree(unquoted(sent.getLast()));
    }

    /** Undoes {@code sh} single quoting: literal inside quotes, {@code \'} outside them is a quote. */
    private static String unquoted(String command) {
        assertTrue(command.startsWith(PRINTF), command);
        String quoted = command.substring(PRINTF.length());
        StringBuilder out = new StringBuilder();
        boolean inside = false;
        for (int i = 0; i < quoted.length(); i++) {
            char c = quoted.charAt(i);
            if (c == '\'') {
                inside = !inside;
            } else if (!inside && c == '\\' && i + 1 < quoted.length()) {
                out.append(quoted.charAt(++i));
            } else {
                out.append(c);
            }
        }
        assertTrue(!inside, () -> "quoting left open: " + command);
        return out.toString();
    }

    private Tool<Map<String, Object>> tool(Path root, int shift) {
        return new ReportTool(hub(root), FIELDS, shift).tool("tell", "tells the answer");
    }

    private ResilientHub hub(Path root) {
        HubSend sender = (label, taskName, answer) -> {
            sent.add(MAPPER.valueToTree(answer).get(TaskParams.CMD_KEY).asString());
            return new HubResponse(status, TestHeaders.of(Map.of()), reply);
        };

        return new ResilientHub(
                sender,
                "x-task",
                RetryPolicy.defaults(),
                new RateLimitHeaders(List.of(), Duration.ofSeconds(60)),
                wait -> {},
                RunTranscript.open(root.resolve("logs"), "x01", Map.of(), List.of()));
    }
}
