package io.github.dbonkowska.dscribe.labs.s05e05;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Device states for tests, built from a settled default with one thing changed at a time.
 *
 * <p>The words for mode and condition are the device's, as {@link DeviceState} reads them. Every
 * value is invented.
 */
final class States {

    private LocalDate currentDate = LocalDate.of(3000, 1, 2);
    private Integer day;
    private Integer month;
    private Integer year;
    private BigDecimal syncRatio = BigDecimal.ZERO;
    private int stabilization;
    private String condition = "unstable";
    private int fluxDensity;
    private int batteryCharged = 1;
    private int batteryCapacity = 3;
    private boolean pta;
    private boolean ptb;
    private int pwr;
    private String mode = DeviceState.STANDBY;
    private int internalMode = 1;
    private String needConfig;

    static States at(LocalDate currentDate) {
        States states = new States();
        states.currentDate = currentDate;
        return states;
    }

    States target(LocalDate date) {
        day = date.getDayOfMonth();
        month = date.getMonthValue();
        year = date.getYear();
        return this;
    }

    States day(Integer value) { day = value; return this; }
    States month(Integer value) { month = value; return this; }
    States year(Integer value) { year = value; return this; }
    States syncRatio(String value) { syncRatio = new BigDecimal(value); return this; }
    States stabilization(int value) { stabilization = value; return this; }
    States stable() { condition = DeviceState.STABLE; return this; }
    States unstable() { condition = "unstable"; return this; }
    States flux(int value) { fluxDensity = value; return this; }
    States battery(int charged) { batteryCharged = charged; return this; }
    States pta(boolean on) { pta = on; return this; }
    States ptb(boolean on) { ptb = on; return this; }
    States pwr(int value) { pwr = value; return this; }
    States active() { mode = DeviceState.ACTIVE; return this; }
    States standby() { mode = DeviceState.STANDBY; return this; }
    States phase(int value) { internalMode = value; return this; }
    States hint(String text) { needConfig = text; return this; }

    DeviceState build() {
        return new DeviceState(currentDate, day, month, year, syncRatio, stabilization, condition, fluxDensity,
                batteryCharged, batteryCapacity, pta, ptb, pwr, mode, internalMode, needConfig);
    }
}
