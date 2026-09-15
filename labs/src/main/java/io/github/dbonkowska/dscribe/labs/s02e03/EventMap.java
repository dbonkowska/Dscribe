package io.github.dbonkowska.dscribe.labs.s02e03;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
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

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final List<Line> lines;
    private final List<Event> events;

    /** Which entry a line was merged into, keyed the way merging is: severity, then message. */
    private final Map<List<String>, String> idByKey;

    private EventMap(List<Line> lines, List<Event> events) {
        this.lines = lines;
        this.events = events;

        Map<List<String>, String> ids = new HashMap<>();
        for (Event event : events) {
            ids.put(key(event.severity(), event.message()), event.id());
        }
        this.idByKey = Map.copyOf(ids);
    }

    /** The one definition of what makes two lines the same event. */
    private static List<String> key(String severity, String message) {
        return List.of(severity, message);
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
            merging.computeIfAbsent(key(line.severity(), line.message()), key -> new Merging(line))
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
            return new Line(minute, m.group("severity"), m.group("message"), raw);
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

    /**
     * What the model reads instead of the source: one line per event, all severities.
     *
     * <p>The layout is this code's own presentation rather than anything the exercise supplies, so
     * it lives here. Times are written in the form zoom parses, so a moment the model wants to look
     * around is one it copies out of this rather than reassembles.
     */
    String renderMap() {
        StringBuilder map = new StringBuilder();
        for (Event event : events) {
            map.append(event.id()).append(' ')
                    .append(event.severity()).append(" x").append(event.count())
                    .append(" first ").append(MINUTE.format(event.first()))
                    .append(" last ").append(MINUTE.format(event.last()))
                    .append(" | ").append(event.message())
                    .append('\n');
        }
        return map.toString();
    }

    /**
     * The text that goes to the hub: each chosen event once, at the minute it first appeared, in
     * the order things happened.
     *
     * <p>The model only chooses. Order and the time a line carries are decided here, so neither can
     * be got wrong by a model that listed its choices in the order it thought of them.
     *
     * @param lineFormat four {@code %s}: date, time, severity, message — validated by
     *                   {@link TaskParams}
     * @throws IllegalArgumentException naming an id the map does not have
     */
    String renderSubmission(List<String> ids, String lineFormat) {
        List<Event> chosen = new ArrayList<>(ids.size());
        for (String id : ids) {
            chosen.add(events.stream()
                    .filter(event -> event.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "There is no entry " + id + " in the map. Use only ids the map lists.")));
        }

        chosen.sort(Comparator.comparing(Event::first).thenComparing(Event::id));

        StringJoiner submission = new StringJoiner("\n");
        for (Event event : chosen) {
            submission.add(lineFormat.formatted(
                    DATE.format(event.first()), TIME.format(event.first()), event.severity(), event.message()));
        }
        return submission.toString();
    }

    /**
     * The source itself, around a moment: every line within {@code minutes} either side of
     * {@code at}, both edges included, in file order, each prefixed with the id of the entry it was
     * merged into — so a line found here is one the model can submit.
     */
    List<String> around(LocalDateTime at, int minutes) {
        LocalDateTime from = at.minusMinutes(minutes);
        LocalDateTime to = at.plusMinutes(minutes);

        return lines.stream()
                .filter(line -> !line.minute().isBefore(from) && !line.minute().isAfter(to))
                .map(line -> idByKey.get(key(line.severity(), line.message())) + " " + line.raw())
                .toList();
    }

    /** The events code submits before the model is involved, in the map's order. */
    List<String> idsWithSeverity(List<String> severities) {
        return events.stream()
                .filter(event -> severities.contains(event.severity()))
                .map(Event::id)
                .toList();
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
