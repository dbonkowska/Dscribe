package io.github.dbonkowska.dscribe.labs.s05e05;

import io.github.dbonkowska.dscribe.labs.hub.HubResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One reading of the device, taken from a reply's {@code config}.
 *
 * <p>Strict: a field that is absent or of the wrong type refuses the whole reading rather than
 * defaulting, since every decision in a run is taken from this and a default would steer it silently.
 * A record, so two readings of the same state are equal, which is what the watcher diffs on.
 *
 * <p>The field names are read off the device's replies and never sent, so they stay here (rule 11).
 *
 * @param currentDate     where the device is now
 * @param day             the target's day, or null while unset
 * @param month           the target's month, or null while unset
 * @param year            the target's year, or null while unset
 * @param syncRatio       as the device holds it, scale and all
 * @param stabilization   as the device holds it
 * @param condition       the device's word for whether stabilization holds
 * @param fluxDensity     readiness, 0 to 100
 * @param batteryCharged  the charged units of {@code batteryCapacity}
 * @param batteryCapacity the units the device counts charge in
 * @param pta             the control for jumps back
 * @param ptb             the control for jumps ahead
 * @param pwr             the power level
 * @param mode            the device's word for its mode
 * @param internalMode    the phase the device is in
 * @param needConfig      the free-text hint, or null when the reply carries none
 */
record DeviceState(
        LocalDate currentDate,
        Integer day,
        Integer month,
        Integer year,
        BigDecimal syncRatio,
        int stabilization,
        String condition,
        int fluxDensity,
        int batteryCharged,
        int batteryCapacity,
        boolean pta,
        boolean ptb,
        int pwr,
        String mode,
        int internalMode,
        String needConfig) {

    /** The device's words for its mode and condition, read off its replies and never sent. */
    static final String STANDBY = "standby";
    static final String ACTIVE = "active";
    static final String STABLE = "stable";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Charged units over capacity. Each side one possessive class, so nothing backtracks. */
    private static final Pattern CHARGE = Pattern.compile("(\\d++)/(\\d++)");

    /**
     * Reads a reply, refusing one that is not a state: a rejected call quotes the device's code and
     * message, and anything unreadable is quoted whole.
     */
    static DeviceState of(HubResponse response) {
        JsonNode body = parse(response.body());
        if (response.status() / 100 != 2) {
            throw new IllegalStateException(
                    "The device refused the call with HTTP " + response.status() + ": code "
                            + body.path("code").asString("?") + ", " + body.path("message").asString("no message")
                            + ".");
        }
        JsonNode config = body.get("config");
        if (config == null || !config.isObject()) {
            throw new IllegalStateException(
                    "The reply has no config object, so it is not a state: " + response.body());
        }
        JsonNode hint = body.get("needConfig");
        if (hint != null && !hint.isNull() && !hint.isString()) {
            throw new IllegalStateException("needConfig is " + hint + ", which is not text.");
        }
        int[] charge = charge(text(config, "batteryStatus"));
        return new DeviceState(
                date(text(config, "currentDate")),
                optionalInt(config, "day"),
                optionalInt(config, "month"),
                optionalInt(config, "year"),
                number(config, "syncRatio").decimalValue(),
                integer(config, "stabilization"),
                text(config, "condition"),
                integer(config, "fluxDensity"),
                charge[0],
                charge[1],
                bool(config, "PTA"),
                bool(config, "PTB"),
                integer(config, "PWR"),
                text(config, "mode"),
                integer(config, "internalMode"),
                hint == null || hint.isNull() ? null : hint.stringValue());
    }

    /** The target, only once day, month and year are all set: a partial date is no target. */
    Optional<LocalDate> date() {
        if (day == null || month == null || year == null) {
            return Optional.empty();
        }
        return Optional.of(LocalDate.of(year, month, day));
    }

    Optional<String> hint() {
        return Optional.ofNullable(needConfig);
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("The reply is not a JSON object: " + body);
            }
            return node;
        } catch (JacksonException e) {
            throw new IllegalStateException("The reply is not JSON: " + body, e);
        }
    }

    private static JsonNode field(JsonNode config, String name) {
        JsonNode value = config.get(name);
        if (value == null) {
            throw new IllegalStateException("The reply's config has no " + name + ", so the state is unknown.");
        }
        return value;
    }

    private static IllegalStateException wrongType(String name, JsonNode value, String expected) {
        return new IllegalStateException(
                "The reply's config has " + name + " = " + value + ", which is not " + expected + ".");
    }

    private static String text(JsonNode config, String name) {
        JsonNode value = field(config, name);
        if (!value.isString()) {
            throw wrongType(name, value, "text");
        }
        return value.stringValue();
    }

    private static JsonNode number(JsonNode config, String name) {
        JsonNode value = field(config, name);
        if (!value.isNumber()) {
            throw wrongType(name, value, "a number");
        }
        return value;
    }

    private static int integer(JsonNode config, String name) {
        JsonNode value = field(config, name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw wrongType(name, value, "a whole number");
        }
        return value.intValue();
    }

    private static Integer optionalInt(JsonNode config, String name) {
        return field(config, name).isNull() ? null : integer(config, name);
    }

    private static boolean bool(JsonNode config, String name) {
        JsonNode value = field(config, name);
        if (!value.isBoolean()) {
            throw wrongType(name, value, "true or false");
        }
        return value.booleanValue();
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "The reply's config has currentDate = " + value + ", which is not an ISO date.", e);
        }
    }

    private static int[] charge(String value) {
        Matcher matcher = CHARGE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "The reply's config has batteryStatus = " + value + ", which is not charged/capacity.");
        }
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }
}
