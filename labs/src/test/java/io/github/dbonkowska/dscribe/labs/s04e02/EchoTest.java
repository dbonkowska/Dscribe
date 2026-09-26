package io.github.dbonkowska.dscribe.labs.s04e02;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A signature comes back through the same queue as everything else, in no order, and says which
 * point it signed only by echoing that point's values — as strings, with the numbers respelled. If
 * what was sent and what came back compare unequal, the collector waits for a result it already has
 * until the window closes, and the hub says only that it timed out.
 *
 * <p>Field names here are invented.
 */
class EchoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void matchesAnEchoThatRespellsTheNumbers() {
        Map<String, Object> sent = sent("d", "x", "n", new BigDecimal("5"), "m", new BigDecimal("0"));

        assertTrue(Echo.matches(sent, json("{\"d\":\"x\",\"n\":\"5.0\",\"m\":\"0.0\"}")));
    }

    @Test
    void matchesTrailingZerosOnAFraction() {
        assertTrue(Echo.matches(sent("n", new BigDecimal("5.3")), json("{\"n\":\"5.30\"}")));
    }

    @Test
    void matchesAnEchoThatKeepsTheNumbersAsNumbers() {
        assertTrue(Echo.matches(sent("n", new BigDecimal("5.3")), json("{\"n\":5.3}")));
    }

    @Test
    void doesNotMatchADifferentValue() {
        assertFalse(Echo.matches(sent("n", new BigDecimal("5.3")), json("{\"n\":\"5.4\"}")));
        assertFalse(Echo.matches(sent("d", "x"), json("{\"d\":\"y\"}")));
    }

    /** Text is compared as written: a date is not a number, and "01" is not "1" for it. */
    @Test
    void comparesTextExactly() {
        assertTrue(Echo.matches(sent("d", "2000-01-01"), json("{\"d\":\"2000-01-01\"}")));
        assertFalse(Echo.matches(sent("d", "2000-01-01"), json("{\"d\":\"2000-1-1\"}")));
    }

    @Test
    void doesNotMatchAnEchoMissingASentField() {
        assertFalse(Echo.matches(sent("d", "x", "n", new BigDecimal("5")), json("{\"d\":\"x\"}")));
    }

    /** The echo may carry more than was sent; only what was sent has to agree. */
    @Test
    void matchesAnEchoCarryingAnExtraField() {
        assertTrue(Echo.matches(sent("d", "x"), json("{\"d\":\"x\",\"extra\":\"1\"}")));
    }

    private static Map<String, Object> sent(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
