package io.github.dbonkowska.dscribe.labs.s05e05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which stretch of the route the device is on is never stored: it is read off every state, so a
 * restarted run carries on where the device is. A wrong reading sends a jump the wrong way, on a
 * charge that cannot be won back, so every class of state is pinned here.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class StageTest {

    private static final LocalDate TODAY = LocalDate.of(3000, 1, 2);
    private static final LocalDate FETCH = LocalDate.of(3100, 3, 4);
    private static final LocalDate MEETING = LocalDate.of(2900, 5, 6);

    private static final Stage.Route ROUTE = new Stage.Route(FETCH, MEETING, 2);

    private static DeviceState at(LocalDate currentDate, int charged) {
        return new DeviceState(currentDate, null, null, null, BigDecimal.ZERO, 0, "x", 0, charged, 3,
                false, false, 0, "y", 1, null);
    }

    @Test
    void returnsTodayFromTheFetchDate() {
        assertEquals(new Stage.Go(Stage.Kind.RETURN, TODAY), ROUTE.of(at(FETCH, 0), TODAY));
    }

    /** The charge at the far end does not change the way back: there is only one way back. */
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void returnsTodayFromTheFetchDateWhateverTheCharge(int charged) {
        assertEquals(new Stage.Go(Stage.Kind.RETURN, TODAY), ROUTE.of(at(FETCH, charged), TODAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void opensTheTunnelFromTodayWithEnoughCharge(int charged) {
        assertEquals(new Stage.Go(Stage.Kind.TUNNEL, MEETING), ROUTE.of(at(TODAY, charged), TODAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void fetchesFromTodayWithoutEnoughCharge(int charged) {
        assertEquals(new Stage.Go(Stage.Kind.FETCH, FETCH), ROUTE.of(at(TODAY, charged), TODAY));
    }

    @Test
    void hasArrivedAtTheMeetingDate() {
        assertInstanceOf(Stage.Arrived.class, ROUTE.of(at(MEETING, 0), TODAY));
    }

    /** Somewhere off the route, no stage fits, and guessing one would jump from an unknown place. */
    @Test
    void stopsAnywhereElseNamingTheDate() {
        LocalDate elsewhere = LocalDate.of(3050, 7, 8);

        Stage stage = ROUTE.of(at(elsewhere, 3), TODAY);

        Stage.Stop stop = assertInstanceOf(Stage.Stop.class, stage);
        assertTrue(stop.reason().contains("3050-07-08"), stop.reason());
    }

    /** Today is passed in, not read from the device: once it has jumped, its date is not today. */
    @Test
    void readsTodayFromTheCallerNotTheDevice() {
        LocalDate laterToday = TODAY.plusDays(1);

        assertInstanceOf(Stage.Stop.class, ROUTE.of(at(TODAY, 3), laterToday));
    }
}
