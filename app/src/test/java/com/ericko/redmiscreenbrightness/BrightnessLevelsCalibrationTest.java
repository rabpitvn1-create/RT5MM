package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrightnessLevelsCalibrationTest {
    @Test
    public void shiftedRawAnchorsNeverDrift() {
        assertEquals(14, BrightnessLevels.getRawForPercent(20));
        assertEquals(20, BrightnessLevels.getRawForPercent(30));
        assertEquals(29, BrightnessLevels.getRawForPercent(40));
        assertEquals(41, BrightnessLevels.getRawForPercent(50));
        assertEquals(52, BrightnessLevels.getRawForPercent(60));
    }

    @Test
    public void percentMappingStaysMonotonic() {
        int previous = 0;
        for (int percent = 5; percent <= 60; percent++) {
            int raw = BrightnessLevels.getRawForPercent(percent);
            assertTrue(raw >= previous);
            previous = raw;
        }
    }
}
