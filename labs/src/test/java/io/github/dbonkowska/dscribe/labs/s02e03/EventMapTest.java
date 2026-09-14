package io.github.dbonkowska.dscribe.labs.s02e03;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map is the only view of the source the model ever gets, so every way it can be wrong is a
 * silent one. A wrong {@code last} still renders, a pattern that skips lines still produces events,
 * and ids in the wrong order still validate — each submission then goes out well-formed, and the
 * hub's rejection reads as the model choosing badly rather than as the code miscounting.
 *
 * <p>The per-lesson rule says parsing needs no tests, because the hub rejects a wrong answer. It
 * does here for the reason {@code RenderingTest} and {@code MoveToolTest} exist: the hub rejects the
 * answer, but not in a way that points at this class.
 *
 * <p>Lines, severities and the pattern are invented and belong to no lesson.
 */
class EventMapTest {

    private static final Pattern PATTERN =
            Pattern.compile("(?<date>\\S+) (?<time>\\S+) (?<severity>\\w+) (?<message>.*)");

    private static EventMap parse(String... lines) {
        return EventMap.parse(String.join("\n", lines), PATTERN);
    }

    private static Event event(EventMap map, String message) {
        return map.events().stream()
                .filter(e -> e.message().equals(message))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no event for " + message + " in " + map.events()));
    }

    /**
     * A pattern that silently skipped what it could not read would leave the model reasoning about
     * a source with holes in it. The line number is what makes the mistake findable in a file of
     * thousands.
     */
    @Test
    void refusesALineThePatternDoesNotMatchAndSaysWhichOne() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> parse(
                "2030-01-01 10:00 HIGH pump stalled",
                "2030-01-01 10:05 LOW fan ok",
                "not a line at all"));

        assertTrue(thrown.getMessage().contains("3"),
                () -> "it has to name the line: " + thrown.getMessage());
    }

    /** Matching is not enough: a time the map cannot place cannot be zoomed to or ordered by. */
    @Test
    void refusesALineWhoseTimeCannotBeReadAndSaysWhichOne() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> parse(
                "2030-01-01 10:00 HIGH pump stalled",
                "2030-01-01 ten LOW fan ok"));

        assertTrue(thrown.getMessage().contains("2"),
                () -> "it has to name the line: " + thrown.getMessage());
    }

    @Test
    void skipsBlankLinesRatherThanRefusingThem() {
        EventMap map = parse(
                "2030-01-01 10:00 HIGH pump stalled",
                "",
                "   ",
                "2030-01-01 10:05 LOW fan ok");

        assertEquals(2, map.lines().size());
    }

    /**
     * Three occurrences, not two. With two, a {@code last} that is only ever set once — on the
     * second sighting — looks exactly like one that is updated every time. The third is where they
     * diverge, and the line between them with another message is what a merge keyed on adjacency
     * would split on.
     */
    @Test
    void mergesRepeatsIntoOneEventWithItsCountAndFirstAndLastTimes() {
        EventMap map = parse(
                "2030-01-01 10:00 HIGH pump stalled",
                "2030-01-01 10:05 LOW fan ok",
                "2030-01-01 10:20 HIGH pump stalled",
                "2030-01-01 10:40 HIGH pump stalled");

        Event stalled = event(map, "pump stalled");
        assertEquals(3, stalled.count());
        assertEquals(LocalDateTime.of(2030, 1, 1, 10, 0), stalled.first());
        assertEquals(LocalDateTime.of(2030, 1, 1, 10, 40), stalled.last());

        assertEquals(1, event(map, "fan ok").count());
        assertEquals(2, map.events().size());
    }

    /** Severity is part of what an event is — the same words at two levels are two things. */
    @Test
    void keepsTheSameMessageAtTwoSeveritiesApart() {
        EventMap map = parse(
                "2030-01-01 10:00 LOW pump slow",
                "2030-01-01 10:05 HIGH pump slow");

        assertEquals(2, map.events().size());
    }

    /**
     * Chosen so every other plausible order disagrees: alphabetically by message the second line
     * comes first, and so it does by severity.
     */
    @Test
    void numbersEventsInTheOrderTheyFirstAppear() {
        EventMap map = parse(
                "2030-01-01 10:00 LOW zeta",
                "2030-01-01 10:05 HIGH alpha",
                "2030-01-01 10:10 LOW zeta");

        assertEquals(List.of("E1", "E2"), map.events().stream().map(Event::id).toList());
        assertEquals("zeta", map.events().getFirst().message());
    }

    /** Padded to the width of the total, so ids sort and read the same way the map lists them. */
    @Test
    void padsIdsToTheWidthOfTheTotal() {
        EventMap map = EventMap.parse(IntStream.rangeClosed(1, 10)
                .mapToObj(i -> "2030-01-01 10:%02d LOW event %d".formatted(i, i))
                .collect(Collectors.joining("\n")), PATTERN);

        List<String> ids = map.events().stream().map(Event::id).toList();
        assertEquals("E01", ids.getFirst());
        assertEquals("E10", ids.getLast());
    }
}
