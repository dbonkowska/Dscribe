package io.github.dbonkowska.dscribe.labs.s05e03;

import io.github.dbonkowska.dscribe.labs.hub.ResilientHub;
import io.github.dbonkowska.dscribe.labs.s05e03.TaskParams.Field;
import io.github.dbonkowska.dscribe.tool.Tool;
import io.github.dbonkowska.dscribe.tool.ToolOutput;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;

/**
 * The model reports each value as the source states it; code writes the answer and prints it on the
 * server, which is how this exercise is answered.
 *
 * <p>Code owns everything about the answer except what was read. The date is moved by the bundle's
 * shift here, because "a day earlier" is calendar arithmetic and month and year ends are exactly where
 * a model's subtraction slips — and the verdict would only say the date was wrong. Decimals are written
 * as JSON numbers exactly as given, text is stripped, and the keys and their order are the bundle's.
 *
 * <p>The command is built here and sent past {@link ShellGuard}, which refuses {@code printf} by design:
 * the guard keeps the model from printing an answer, and this is the one place an answer is printed.
 * {@code printf '%s\n'} rather than {@code echo}, since some shells' {@code echo} interprets
 * backslashes, which would change any escaped character in the JSON.
 *
 * <p>The hub's reply comes back whole, a refusal included: a wrong answer names its field, and the
 * model goes back to the files with that. The run ends on a flag in this tool's reply, and on no other.
 */
final class ReportTool {

    /** Year, month and day, all present and a real date: {@code 2020-02-30} is refused, not rolled over. */
    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * Decimals written plain: a {@code BigDecimal} written as such may come out as {@code 1E+1}. The
     * scale survives on its own — Jackson 3's node factory keeps a {@code BigDecimal} as given, and
     * only reading can strip trailing zeros — and {@code ReportToolTest} pins that.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private final ResilientHub hub;
    private final List<Field> fields;
    private final int shift;

    /**
     * @param fields the answer's fields in the order they are sent, exactly one of them a date
     * @param shift  the days the reported date is moved before it is sent, negative for earlier
     */
    ReportTool(ResilientHub hub, List<Field> fields, int shift) {
        this.hub = hub;
        this.fields = List.copyOf(fields);
        this.shift = shift;
    }

    /** Name and description come from the lesson bundle; the schema from its fields. */
    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>> tool(String name, String description) {
        Class<Map<String, Object>> type = (Class<Map<String, Object>>) (Class<?>) Map.class;
        return new Tool<>(name, description, type, this::report, schema());
    }

    /** One required string per field: the model reports each value as the source states it. */
    private ObjectNode schema() {
        ObjectNode schema = NODES.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Field field : fields) {
            properties.putObject(field.name()).put("type", "string");
            required.add(field.name());
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /** Every value is read before anything is built, so a refusal leaves nothing half sent. */
    private ToolOutput report(Map<String, Object> values) {
        ObjectNode answer = NODES.objectNode();
        for (Field field : fields) {
            String value = text(values.get(field.name()), field.name());
            switch (field.format()) {
                case TaskParams.DATE -> answer.put(field.name(), shifted(value, field.name()));
                case "decimal" -> answer.put(field.name(), decimal(value, field.name()));
                default -> answer.put(field.name(), value);
            }
        }
        String json = MAPPER.writeValueAsString(answer);
        String command = "printf '%s\\n' '" + json.replace("'", "'\\''") + "'";
        return ToolOutput.of(hub.call("report", Map.of(TaskParams.CMD_KEY, command)));
    }

    /**
     * A value the schema says is a string, refused as such when it is not one: a provider that ignored
     * the schema and sent a number has already lost the precision this answer is compared on.
     */
    private static String text(Object value, String field) {
        if (value == null || value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException(
                    field + " is missing from the report. Nothing was sent. Report every field.");
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(
                    field + " is " + value + ", not text. Nothing was sent. Write every value as a string,"
                            + " as the source states it.");
        }
        return s.strip();
    }

    private String shifted(String value, String field) {
        try {
            return LocalDate.parse(value, ISO_DATE).plusDays(shift).format(ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    field + " is " + value + ", which is not a real date written YYYY-MM-DD. Nothing was"
                            + " sent. Report the date as the record states it, in that form; the run moves it"
                            + " itself.", e);
        }
    }

    /** A comma is read as the decimal point; anything else that is not a plain number is refused. */
    private static BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    field + " is " + value + ", which is not a number. Nothing was sent. Report it as the"
                            + " source writes it, digits only.", e);
        }
    }
}
