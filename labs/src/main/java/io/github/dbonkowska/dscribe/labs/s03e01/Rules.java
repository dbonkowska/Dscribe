package io.github.dbonkowska.dscribe.labs.s03e01;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Everything about a record that can be decided without asking a model.
 *
 * <p>Cheap, exact, and small: it settles a fraction of the corpus and leaves the rest. That is
 * worth saying plainly, because the tempting reading of this class is that it does the bulk of
 * the work and the model mops up a residual. It does not. What it buys is the records it catches
 * for nothing, and one thing more — every record it flags is a record whose note need never be
 * read, because a note agreeing with bad measurements adds no identifier the measurements have
 * not already produced.
 *
 * <p>Whatever this pass misses is invisible. The judgement pass only looks at what it called
 * clean, and the hub replies to the whole submission with one word. There is no route by which a
 * rule that failed to fire announces itself.
 */
final class Rules {

    enum Kind {
        /** An active channel reporting a value outside the range configured for it. */
        OUT_OF_RANGE,
        /** A channel the record does not claim to be reporting, reporting something anyway. */
        INACTIVE_NOT_ZERO
    }

    record Violation(String channel, Kind kind) {}

    private final List<TaskParams.Channel> channels;
    private final Set<String> known;
    private final Pattern separator;

    Rules(List<TaskParams.Channel> channels, String separator) {
        this.channels = List.copyOf(channels);
        this.known = channels.stream()
                .map(TaskParams.Channel::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // quoted: the separator is a literal from the bundle, not a pattern the exercise wrote
        this.separator = Pattern.compile(Pattern.quote(separator));
    }

    /**
     * Every rule this record breaks, in channel order. Empty means the measurements are clean and
     * the record's note is worth reading; anything else means it is already answered for.
     *
     * <p>All channels are examined, not just until the first fault. A record reported one fault at
     * a time makes the distribution report a count of records rather than of causes, and the
     * report is the only place a rule that fires on nothing — or on everything — becomes visible.
     */
    List<Violation> violations(Reading reading) {
        Set<String> active = active(reading);
        List<Violation> violations = new ArrayList<>();

        for (TaskParams.Channel channel : channels) {
            double value = value(reading, channel);

            if (active.contains(channel.name())) {
                if (value < channel.min() || value > channel.max()) {
                    violations.add(new Violation(channel.name(), Kind.OUT_OF_RANGE));
                }
            } else if (value != 0) {
                violations.add(new Violation(channel.name(), Kind.INACTIVE_NOT_ZERO));
            }
        }
        return violations;
    }

    private Set<String> active(Reading reading) {
        Set<String> active = new LinkedHashSet<>(
                Arrays.asList(separator.split(reading.sensorType())));

        // a name nothing defines is a channel that would never be range-checked. Zero records
        // carry one, so this is the configuration being wrong — a misspelling, or the wrong
        // separator splitting nothing — and a run that continues submits a quietly short set
        for (String name : active) {
            if (!known.contains(name)) {
                throw new IllegalStateException(
                        "Record " + reading.id() + " is typed '" + reading.sensorType()
                                + "', which names channel '" + name + "'. Nothing defines it, so it"
                                + " would never be range-checked. Known channels: " + known
                                + ". Check channels.N.name and fields.typeSeparator in the lesson's"
                                + " task.properties.");
            }
        }
        return active;
    }

    /**
     * A configured field's value, or a refusal.
     *
     * <p>Strict about inactive channels too, deliberately. This corpus writes every field on every
     * record and zeroes the ones a record is not reporting, so an absent field here means the
     * configuration names something the data does not have. An archive that instead omitted the
     * channels it was not reporting would be diagnosed as a misconfiguration by this message, and
     * would want a different rule — absent-means-zero — decided in the open rather than by
     * loosening this one.
     */
    private static double value(Reading reading, TaskParams.Channel channel) {
        Double value = reading.values().get(channel.field());
        if (value == null) {
            throw new IllegalStateException(
                    "Record " + reading.id() + " carries no '" + channel.field() + "', the field"
                            + " configured for channel '" + channel.name() + "'. Left alone this is"
                            + " a null several frames deeper, in a loop over the whole corpus,"
                            + " naming neither the field nor the record. Check channels.N.field.");
        }
        return value;
    }
}
