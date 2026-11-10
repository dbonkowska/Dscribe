package io.github.dbonkowska.dscribe.labs.s05e05;

import java.time.LocalDate;

/**
 * Where on the route the device is, read off one state.
 *
 * <p>Never stored. Every reading derives it again from where the device is and how much charge it
 * holds, which is what lets a restarted run carry on with no reset and no memory of earlier stages.
 */
sealed interface Stage {

    /** The three stretches of the route, each one jump with its own target. */
    enum Kind {
        /** From today to the fetch date, for charge. */
        FETCH,
        /** From the fetch date back to today. */
        RETURN,
        /** From today to the meeting date, both direction controls on. */
        TUNNEL
    }

    /** A stage to work on, and the date it ends at. */
    record Go(Kind kind, LocalDate target) implements Stage {}

    /** The device is at the meeting date: the route is done. */
    record Arrived() implements Stage {}

    /** No stage fits this state, and guessing one would jump from an unknown place. */
    record Stop(String reason) implements Stage {}

    /**
     * The route's fixed points.
     *
     * @param fetchDate     where the charge is
     * @param meetingDate   where the route ends
     * @param tunnelBattery the charge the last stage needs, in the device's units
     */
    record Route(LocalDate fetchDate, LocalDate meetingDate, int tunnelBattery) {

        /**
         * The stage {@code state} is on. {@code today} comes from the caller's clock, not the device,
         * whose date stops being today once it has jumped.
         */
        Stage of(DeviceState state, LocalDate today) {
            LocalDate at = state.currentDate();
            if (at.equals(meetingDate)) {
                return new Arrived();
            }
            if (at.equals(fetchDate)) {
                return new Go(Kind.RETURN, today);
            }
            if (at.equals(today)) {
                return state.batteryCharged() >= tunnelBattery
                        ? new Go(Kind.TUNNEL, meetingDate)
                        : new Go(Kind.FETCH, fetchDate);
            }
            return new Stop(
                    "The device is at " + at + ", which is not on the route: not today (" + today + "), the"
                            + " fetch date (" + fetchDate + ") or the meeting date (" + meetingDate + ").");
        }
    }
}
