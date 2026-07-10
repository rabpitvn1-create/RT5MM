package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtectionCurveEngineTest {
    @Test
    public void preservesOriginalCalibratedRawAnchors() {
        assertEquals(11, ProtectionCurveEngine.getTargetRaw(10f));
        assertEquals(17, ProtectionCurveEngine.getTargetRaw(65f));
        assertEquals(26, ProtectionCurveEngine.getTargetRaw(350f));
        assertEquals(38, ProtectionCurveEngine.getTargetRaw(1800f));
        assertEquals(49, ProtectionCurveEngine.getTargetRaw(9000f));
    }

    @Test
    public void curveIsMonotonicAcrossFullSensorRange() {
        int previous = ProtectionCurveEngine.getTargetRaw(0f);
        for (int lux = 1; lux <= 120000; lux += 17) {
            int current = ProtectionCurveEngine.getTargetRaw(lux);
            assertTrue("curve fell at lux=" + lux, current >= previous);
            assertTrue(current >= 4 && current <= 49);
            previous = current;
        }
    }

    @Test
    public void invalidLuxFailsSafeToMinimum() {
        assertEquals(4, ProtectionCurveEngine.getTargetRaw(Float.NaN));
        assertEquals(4, ProtectionCurveEngine.getTargetRaw(-100f));
    }
}
