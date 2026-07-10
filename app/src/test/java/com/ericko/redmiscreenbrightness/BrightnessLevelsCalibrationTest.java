package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrightnessLevelsCalibrationTest {
    @Test
    public void originalRawAnchorsNeverDrift() {
        assertEquals(11, BrightnessLevels.getRawForPercent(20));
        assertEquals(17, BrightnessLevels.getRawForPercent(30));
        assertEquals(26, BrightnessLevels.getRawForPercent(40));
        assertEquals(38, BrightnessLevels.getRawForPercent(50));
        assertEquals(49, BrightnessLevels.getRawForPercent(60));
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
