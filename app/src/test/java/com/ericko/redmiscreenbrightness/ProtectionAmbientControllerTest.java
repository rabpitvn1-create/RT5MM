package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtectionAmbientControllerTest {
    @Test
    public void suddenStrongLightCreatesReadableRescue() {
        ProtectionAmbientController controller = new ProtectionAmbientController();

        controller.onLuxSample(0L, 10f, 7);
        controller.onLuxSample(200L, 10f, 7);
        controller.onLuxSample(400L, 1000f, 7);
        ProtectionAmbientController.Result result =
                controller.onLuxSample(600L, 1200f, 7);

        assertEquals(ProtectionAmbientController.Action.SUNLIGHT_RESCUE, result.action);
        assertTrue(result.intermediateRaw > 7);
        assertTrue(result.intermediateRaw < result.finalTargetRaw || result.intermediateRaw == 31);
    }

    @Test
    public void suddenDarknessCreatesSafeIntermediateSettle() {
        ProtectionAmbientController controller = new ProtectionAmbientController();

        controller.onLuxSample(0L, 1000f, 40);
        controller.onLuxSample(200L, 1000f, 40);
        controller.onLuxSample(400L, 10f, 40);
        ProtectionAmbientController.Result result =
                controller.onLuxSample(600L, 8f, 40);

        assertEquals(ProtectionAmbientController.Action.DARK_SETTLE, result.action);
        assertTrue(result.intermediateRaw >= 8);
        assertTrue(result.intermediateRaw <= 14);
        assertTrue(result.intermediateRaw < 40);
    }
}
