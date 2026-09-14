package io.github.dbonkowska.dscribe.labs.s02e03;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A source too large to hand to a model, reduced to what it actually says: every distinct line
 * once, with how often and between when it was said.
 *
 * <p>Merging is by exact severity and message, and that is a decision about this lesson rather
 * than about sources in general. It works because the messages here are fixed templates repeated
 * word for word; a source whose messages carry changing numbers would merge nothing, and would
 * need either a normalising step or summarisation by a model — neither of which is built until a
 * lesson has that shape.
 *
 * <p>What is merged is decided by code and never by the model. The model's job is the judgement
 * that follows — which events matter — and it makes that over this, not over the source.
 */
final class EventMap {

    /** Date and time as the named groups deliver them, joined by a space. */
    static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final List<Line> lines;
    private final List<Event> events;

    private EventMap(List<Line> lines, List<Event> events) {
        this.lines = lines;
        this.events = events;
    }

    /**
     * Strict: a non-blank line the pattern cannot read, or whose time cannot be placed, fails the
     * whole parse. Skipping it would leave the model reasoning about a source with holes in it, and
     * nothing in what it was shown would say so.
     *
     * @param pattern a regex declaring the groups {@code date}, {@code time} (to the minute),
     *                {@code severity} and {@code message} — validated by {@link TaskParams}
     * @throws IllegalArgumentException naming the first line that could not be read
     */
    static EventMap parse(String text, Pattern pattern) {
        List<String> physical = text.lines().toList();
        List<Line> lines = new ArrayList<>();
        Map<List<String>, Merging> merging = new LinkedHashMap<>();

        for (int i = 0; i < physical.size(); i++) {
            String raw = physical.get(i);
            if (raw.isBlank()) {
                continue;
            }
            Line line = read(i + 1, raw, pattern);
            lines.add(line);

            // insertion order is first-occurrence order, which is what ids are numbered by
            merging.computeIfAbsent(List.of(line.severity(), line.message()), key -> new Merging(line))
                    .add(line);
        }

        int width = String.valueOf(merging.size()).length();
        List<Event> events = new ArrayList<>(merging.size());
        for (Merging m : merging.values()) {
            events.add(m.toEvent("E%0" + width + "d", events.size() + 1));
        }

        return new EventMap(List.copyOf(lines), List.copyOf(events));
    }

    private static Line read(int number, String raw, Pattern pattern) {
        Matcher m = pattern.matcher(raw);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Line " + number + " does not match linePattern " + pattern.pattern() + ": " + raw);
        }
        try {
            LocalDateTime minute = LocalDateTime.parse(m.group("date") + " " + m.group("time"), MINUTE);
            return new Line(number, minute, m.group("severity"), m.group("message"), raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Line " + number + " has a date or time that cannot be read as YYYY-MM-DD HH:MM"
                            + " — check which part of the line linePattern captures as them: " + raw, e);
        }
    }

    List<Line> lines() {
        return lines;
    }

    /** In first-occurrence order, which is the order the ids count in. */
    List<Event> events() {
        return events;
    }

    /** One event while its lines are still being counted. */
    private static final class Merging {

        private final Line first;
        private LocalDateTime last;
        private int count;

        Merging(Line first) {
            this.first = first;
        }

        void add(Line line) {
            // every sighting moves it, not only the second: the source is in time order, so the
            // most recent line read is the latest one
            last = line.minute();
            count++;
        }

        Event toEvent(String idFormat, int position) {
            return new Event(idFormat.formatted(position), first.severity(), first.message(),
                    first.minute(), last, count);
        }
    }
}
