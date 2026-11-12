package io.github.dbonkowska.dscribe.labs.s05e05;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The device refuses a ratio that does not match its formula, and a stale one drops readiness to
 * nothing, so the value has to be exact: the right remainder, and sent with two places, never as
 * {@code 0.0}. Expected values are worked out by hand from the weights.
 *
 * <p>Values here are invented: nothing in this file is supplied by the exercise.
 */
class SyncRatioTest {

    /** Distinct weights, so a weight applied to the wrong part of the date changes the result. */
    private static final SyncRatio WEIGHTED = new SyncRatio(3, 5, 7, 11);

    /** 3·1 + 5·1 + 7·2005 = 14043 = 11·1276 + 7. */
    @Test
    void sumsEachPartTimesItsWeightModuloTheModulus() {
        assertEquals(new BigDecimal("0.07"), WEIGHTED.of(LocalDate.of(2005, 1, 1)));
    }

    /** 3·3 + 5·2 + 7·2001 = 14026 = 11·1275 + 1. */
    @Test
    void readsDayMonthAndYearEachWithItsOwnWeight() {
        assertEquals(new BigDecimal("0.01"), WEIGHTED.of(LocalDate.of(2001, 2, 3)));
    }

    /** 3·1 + 5·1 + 7·2004 = 14036 = 11·1276. */
    @Test
    void writesARemainderOfZeroWithTwoPlaces() {
        BigDecimal ratio = WEIGHTED.of(LocalDate.of(2004, 1, 1));

        assertEquals(new BigDecimal("0.00"), ratio);
        assertEquals(2, ratio.scale());
    }

    /** The year alone, so it can be stepped by one across the top of the largest modulus. */
    @Test
    void reachesOneAtTheTopRemainderAndWrapsToZeroAfterIt() {
        SyncRatio yearOnly = new SyncRatio(0, 0, 1, 101);

        assertEquals(new BigDecimal("1.00"), yearOnly.of(LocalDate.of(100, 1, 1)));
        assertEquals(new BigDecimal("0.00"), yearOnly.of(LocalDate.of(101, 1, 1)));
    }

    /** The device reads the number, so two places must survive serialisation as a number. */
    @Test
    void serialisesAsATwoPlaceNumber() {
        String json = JsonMapper.builder().build()
                .writeValueAsString(Map.of("v", WEIGHTED.of(LocalDate.of(2004, 1, 1))));

        assertEquals("{\"v\":0.00}", json);
    }
}
