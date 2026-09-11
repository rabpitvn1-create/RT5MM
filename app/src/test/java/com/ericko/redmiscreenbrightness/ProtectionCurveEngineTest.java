package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtectionCurveEngineTest {
    @Test
    public void preservesShiftedCalibratedRawAnchors() {
        assertEquals(14, ProtectionCurveEngine.getTargetRaw(10f));
        assertEquals(20, ProtectionCurveEngine.getTargetRaw(65f));
        assertEquals(29, ProtectionCurveEngine.getTargetRaw(350f));
        assertEquals(41, ProtectionCurveEngine.getTargetRaw(1800f));
        assertEquals(52, ProtectionCurveEngine.getTargetRaw(9000f));
    }

    @Test
    public void curveIsMonotonicAcrossFullSensorRange() {
        int previous = ProtectionCurveEngine.getTargetRaw(0f);
        for (int lux = 1; lux <= 120000; lux += 17) {
            int current = ProtectionCurveEngine.getTargetRaw(lux);
            assertTrue("curve fell at lux=" + lux, current >= previous);
            assertTrue(current >= 7 && current <= 52);
            previous = current;
        }
    }

    @Test
    public void invalidLuxFailsSafeToMinimum() {
        assertEquals(7, ProtectionCurveEngine.getTargetRaw(Float.NaN));
        assertEquals(7, ProtectionCurveEngine.getTargetRaw(-100f));
    }
}
