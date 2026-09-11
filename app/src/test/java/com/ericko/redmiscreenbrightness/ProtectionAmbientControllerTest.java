package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtectionAmbientControllerTest {
    @Test
    public void suddenStrongLightCreatesReadableRescue() {
        ProtectionAmbientController controller = new ProtectionAmbientController();
        long base = 10_000L;

        controller.onLuxSample(base, 10f, 10);
        controller.onLuxSample(base + 200L, 10f, 10);
        controller.onLuxSample(base + 400L, 1000f, 10);
        ProtectionAmbientController.Result result =
                controller.onLuxSample(base + 600L, 1200f, 10);

        assertEquals(ProtectionAmbientController.Action.SUNLIGHT_RESCUE, result.action);
        assertTrue(result.intermediateRaw > 10);
        assertTrue(result.intermediateRaw <= result.finalTargetRaw);
    }

    @Test
    public void suddenDarknessCreatesSafeIntermediateSettle() {
        ProtectionAmbientController controller = new ProtectionAmbientController();
        long base = 10_000L;

        controller.onLuxSample(base, 1000f, 43);
        controller.onLuxSample(base + 200L, 1000f, 43);
        controller.onLuxSample(base + 400L, 10f, 43);
        ProtectionAmbientController.Result result =
                controller.onLuxSample(base + 600L, 8f, 43);

        assertEquals(ProtectionAmbientController.Action.DARK_SETTLE, result.action);
        assertTrue(result.intermediateRaw >= 11);
        assertTrue(result.intermediateRaw <= 17);
        assertTrue(result.intermediateRaw < 43);
    }

    @Test
    public void zeroTimestampDoesNotRestartInitialWarmup() {
        ProtectionAmbientController controller = new ProtectionAmbientController();

        ProtectionAmbientController.Result first = controller.onLuxSample(0L, 10f, 10);
        ProtectionAmbientController.Result second = controller.onLuxSample(200L, 10f, 10);

        assertEquals(ProtectionAmbientController.Action.HOLD, first.action);
        assertEquals(ProtectionAmbientController.Action.INITIALIZED, second.action);
        assertTrue(second.ambientValid);
    }
}
