package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtectionAmbientControllerTest {
    @Test
    public void suddenStrongLightCreatesReadableRescue() {
        ProtectionAmbientController controller = new ProtectionAmbientController();
        long base = 10_000L;

        controller.onLuxSample(base, 10f, 7);
        controller.onLuxSample(base + 200L, 10f, 7);
        controller.onLuxSample(base + 400L, 1000f, 7);
        ProtectionAmbientController.Result result =
                controller.onLuxSample(base + 600L, 1200f, 7);

        assertEquals(ProtectionAmbientController.Action.SUNLIGHT_RESCUE, result.action);
        assertTrue(result.intermediateRaw > 7);
        assertTrue(result.intermediateRaw <= result.finalTargetRaw);
    }

    @Test
    public void suddenDarknessCreatesSafeIntermediateSettle() {
        ProtectionAmbientController controller = new ProtectionAmbientController();
        long base = 10_000L;

        controller.onLuxSample(base, 1000f, 40);
        controller.onLuxSample(base + 200L, 1000f, 40);
        controller.onLuxSample(base + 400L, 10f, 40);
        ProtectionAmbientController.Result result =
                controller.onLuxSample(base + 600L, 8f, 40);

        assertEquals(ProtectionAmbientController.Action.DARK_SETTLE, result.action);
        assertTrue(result.intermediateRaw >= 8);
        assertTrue(result.intermediateRaw <= 14);
        assertTrue(result.intermediateRaw < 40);
    }

    @Test
    public void zeroTimestampDoesNotRestartInitialWarmup() {
        ProtectionAmbientController controller = new ProtectionAmbientController();

        ProtectionAmbientController.Result first = controller.onLuxSample(0L, 10f, 7);
        ProtectionAmbientController.Result second = controller.onLuxSample(200L, 10f, 7);

        assertEquals(ProtectionAmbientController.Action.HOLD, first.action);
        assertEquals(ProtectionAmbientController.Action.INITIALIZED, second.action);
        assertTrue(second.ambientValid);
    }
}
