package io.github.dbonkowska.dscribe.labs.s05e05;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The ratio a date has to be sent with: each part of the date times its weight, summed, modulo
 * {@code modulus}, as a number with two decimal places.
 *
 * <p>The docs divide the remainder by 100. Scale 2 is that division, done exactly: a {@code double}
 * would write a remainder of 0 as {@code 0.0}. It is also why {@link TaskParams} refuses a modulus
 * above {@value TaskParams#MAX_MODULUS}, since two places hold nothing above {@code 1.00}.
 */
record SyncRatio(int dayWeight, int monthWeight, int yearWeight, int modulus) {

    SyncRatio(TaskParams.Sync sync) {
        this(sync.dayWeight(), sync.monthWeight(), sync.yearWeight(), sync.modulus());
    }

    BigDecimal of(LocalDate date) {
        long sum = (long) date.getDayOfMonth() * dayWeight
                + (long) date.getMonthValue() * monthWeight
                + (long) date.getYear() * yearWeight;
        return BigDecimal.valueOf(sum % modulus, 2);
    }
}
