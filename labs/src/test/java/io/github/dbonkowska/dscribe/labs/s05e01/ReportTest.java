package io.github.dbonkowska.dscribe.labs.s05e01;

import io.github.dbonkowska.dscribe.labs.s05e01.TaskParams.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report decides two things the hub never sees directly: whether to pay for the next tier, and
 * how each value is written on the way out. A wrong escalation spends money for nothing or stops
 * short; a wrong format is rejected as a wrong answer, with nothing to say it was only the spelling.
 *
 * <p>Field names are invented. The real ones are the exercise's and live in its bundle.
 */
class ReportTest {

    private static final List<Field> FIELDS = List.of(
            new Field("place", "text"),
            new Field("size", "decimal2"),
            new Field("count", "integer"),
            new Field("contact", "digits"));

    private static Report report(String place, String size, String count, String contact) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("place", place);
        values.put("size", size);
        values.put("count", count);
        values.put("contact", contact);
        return Report.from(FIELDS, values);
    }

    @Test
    void namesEveryFieldThatIsEmptyOrBlankAsMissing() {
        assertEquals(List.of("size", "contact"), report("A", "", "3", " ").missing());
    }

    /** A provider that ignores the schema can leave a key out; that is a field not found, not a crash. */
    @Test
    void treatsAFieldTheModelLeftOutAsMissing() {
        assertEquals(List.of("count", "contact"), Report.from(FIELDS, Map.of("place", "A", "size", "1")).missing());
    }

    @Test
    void keepsWhatAnEarlierTierFoundUnlessALaterOneFillsIt() {
        Report earlier = report("A", "1", "", "");
        Report later = report("", "2", "3", "");

        assertEquals(report("A", "2", "3", ""), earlier.mergedWith(later));
    }

    /** True rounding, not truncation, and always two places — the hub compares the string. */
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "12.345;12.35",
            "12.344;12.34",
            "12,5;12.50",
            "7;7.00",
            " 3.005 ;3.01"})
    void writesADecimalRoundedHalfUpToTwoPlaces(String found, String sent) {
        assertEquals(sent, report("A", found, "3", "1").answer().get("size"));
    }

    @Test
    void writesAnIntegerAsANumber() {
        assertEquals(321, report("A", "1", " 321 ", "1").answer().get("count"));
    }

    @Test
    void keepsOnlyTheDigitsOfADigitsField() {
        assertEquals("48123456789", report("A", "1", "3", "+48 123-456-789").answer().get("contact"));
    }

    @Test
    void keepsATextFieldStripped() {
        assertEquals("A b", report(" A b ", "1", "3", "1").answer().get("place"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "about 12;1;1;size",
            "1;3 21;1;count",
            "1;x;1;count",
            "1;1;abc;contact"})
    void refusesAValueItsFormatCannotRead(String size, String count, String contact, String field) {
        Report report = report("A", size, count, contact);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, report::answer);

        assertTrue(thrown.getMessage().contains(field), thrown::getMessage);
    }

    @Test
    void refusesToAnswerWhileAFieldIsMissing() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> report("A", "", "3", "").answer());

        assertTrue(thrown.getMessage().contains("size"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("contact"), thrown::getMessage);
    }

    /** The runner adds the action; the answer carries the configured fields and nothing else. */
    @Test
    void answersWithExactlyTheConfiguredFieldsInOrder() {
        assertEquals(List.of("place", "size", "count", "contact"),
                List.copyOf(report("A", "1", "3", "1").answer().keySet()));
    }

    @Test
    void asksTheModelForOneRequiredStringPerFieldAndNothingElse() {
        JsonNode schema = Report.schema(FIELDS);

        assertEquals("object", schema.path("type").asString());
        for (Field field : FIELDS) {
            assertEquals("string", schema.at("/properties/" + field.name() + "/type").asString());
        }
        assertEquals(4, schema.path("properties").size());
        assertEquals(List.of("place", "size", "count", "contact"),
                schema.path("required").valueStream().map(JsonNode::asString).toList());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }
}
