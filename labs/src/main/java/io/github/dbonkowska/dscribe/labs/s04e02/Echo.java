package io.github.dbonkowska.dscribe.labs.s04e02;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Whether a signing result's echo is of the values a point was signed with — how a signature is
 * matched back to its point, since results arrive in no order.
 *
 * <p>The hub respells what it echoes: {@code 5} comes back as {@code "5.0"}. So a value sent as a
 * number is compared as a number, whatever the echo wrote it as; a value sent as text is compared as
 * text, exactly. Which is which is decided by what was sent, never by what the echo looks like — a
 * date echoed back is not a number however it is spelled.
 */
public final class Echo {

    private Echo() {}

    /** True when every sent field is in the echo with an equal value. Extra echoed fields are ignored. */
    public static boolean matches(Map<String, Object> sent, JsonNode echoed) {
        for (Map.Entry<String, Object> field : sent.entrySet()) {
            JsonNode value = echoed.get(field.getKey());
            if (value == null || value.isNull() || !equal(field.getValue(), value.asString(""))) {
                return false;
            }
        }
        return true;
    }

    private static boolean equal(Object sent, String echoed) {
        if (sent instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).compareTo(new BigDecimal(echoed.strip())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return String.valueOf(sent).equals(echoed);
    }
}
