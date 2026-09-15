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

        assertTrue(thrown.getMessage().contains("Line 3"),
                () -> "it has to name the line: " + thrown.getMessage());
    }

    /** Matching is not enough: a time the map cannot place cannot be zoomed to or ordered by. */
    @Test
    void refusesALineWhoseTimeCannotBeReadAndSaysWhichOne() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> parse(
                "2030-01-01 10:00 HIGH pump stalled",
                "2030-01-01 ten LOW fan ok"));

        // "Line 2" rather than "2": the message quotes the raw line, whose date already contains a 2,
        // so a bare digit would pass whatever number was reported
        assertTrue(thrown.getMessage().contains("Line 2"),
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

    /**
     * First and last are the earliest and latest times, not the first and last lines read. A source
     * written slightly out of order would otherwise submit an event at a time it did not begin, and
     * sort it past what it caused — well-formed, and wrong in a way nothing downstream can see.
     *
     * <p>Read order is chosen so both reading-order answers are wrong: the first line read is the
     * latest, and the last line read is neither the earliest nor the latest.
     */
    @Test
    void takesTheEarliestAndLatestTimesWhateverOrderTheLinesWereRead() {
        EventMap map = parse(
                "2030-01-01 10:40 HIGH pump stalled",
                "2030-01-01 10:00 HIGH pump stalled",
                "2030-01-01 10:20 HIGH pump stalled");

        Event stalled = event(map, "pump stalled");
        assertEquals(LocalDateTime.of(2030, 1, 1, 10, 0), stalled.first());
        assertEquals(LocalDateTime.of(2030, 1, 1, 10, 40), stalled.last());
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

    /**
     * The whole of what the model knows about the source, so every field has to be there: the id
     * it refers to the event by, the count that separates background from one-offs, and both times
     * in the same form zoom accepts — copied, not reassembled.
     */
    @Test
    void rendersOneMapLinePerEventWithEverythingTheModelChoosesBy() {
        EventMap map = parse(
                "2030-01-01 10:00 HIGH pump stalled",
                "2030-01-01 10:05 LOW fan ok",
                "2030-01-01 10:20 HIGH pump stalled",
                "2030-01-01 10:40 HIGH pump stalled");

        assertEquals(List.of(
                        "E1 HIGH x3 first 2030-01-01 10:00 last 2030-01-01 10:40 | pump stalled",
                        "E2 LOW x1 first 2030-01-01 10:05 last 2030-01-01 10:05 | fan ok"),
                map.renderMap().lines().toList());
    }

    /**
     * Two properties the model must not be able to get wrong, so neither is left to it: lines come
     * out in the order things happened whatever order it listed them in, and each carries the time
     * the event began — the last time would move a cause to after its effect.
     */
    @Test
    void rendersASubmissionInTimeOrderAtEachEventsFirstOccurrence() {
        EventMap map = parse(
                "2030-01-01 10:00 HIGH pump stalled",
                "2030-01-01 10:05 LOW fan ok",
                "2030-01-01 10:20 HIGH pump stalled",
                "2030-01-01 10:40 HIGH pump stalled");

        assertEquals(
                "[2030-01-01 10:00] [HIGH] pump stalled\n[2030-01-01 10:05] [LOW] fan ok",
                map.renderSubmission(List.of("E2", "E1"), "[%s %s] [%s] %s"));
    }

    /**
     * The schema narrows ids to the map's own, but a provider is not obliged to honour it — and an
     * id that silently rendered as nothing would send a shorter log than the model believes it sent.
     */
    @Test
    void refusesToRenderAnIdTheMapDoesNotHave() {
        EventMap map = parse(
                "2030-01-01 10:00 HIGH pump stalled",
                "2030-01-01 10:05 LOW fan ok");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> map.renderSubmission(List.of("E1", "E9"), "%s %s %s %s"));

        assertTrue(thrown.getMessage().contains("E9"), thrown::getMessage);
    }

    /** What code submits before the model is involved: chosen by severity, in the map's order. */
    @Test
    void selectsTheIdsOfEventsAtTheGivenSeverities() {
        EventMap map = parse(
                "2030-01-01 10:00 HIGH pump stalled",
                "2030-01-01 10:05 LOW fan ok",
                "2030-01-01 10:10 MID valve slow",
                "2030-01-01 10:15 HIGH tank low");

        assertEquals(List.of("E1", "E3", "E4"), map.idsWithSeverity(List.of("MID", "HIGH")));
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
