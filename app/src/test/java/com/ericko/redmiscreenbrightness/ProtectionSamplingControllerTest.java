package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProtectionSamplingControllerTest {
    @Test
    public void stableAmbientMovesFromFastToActiveToEco() {
        ProtectionSamplingController controller = new ProtectionSamplingController();
        controller.reset(0L);

        assertEquals(ProtectionSamplingController.Mode.FAST_TRACK, controller.getMode());

        controller.onAmbientResult(
                4_000L,
                ProtectionAmbientController.Action.HOLD,
                "AMBIENT_HYSTERESIS_HOLD",
                false);
        assertEquals(ProtectionSamplingController.Mode.ACTIVE_TRACK, controller.getMode());

        controller.onAmbientResult(
                12_000L,
                ProtectionAmbientController.Action.HOLD,
                "AMBIENT_HYSTERESIS_HOLD",
                false);
        assertEquals(ProtectionSamplingController.Mode.STABLE_ECO, controller.getMode());
    }

    @Test
    public void meaningfulAmbientChangeImmediatelyReturnsToFastTrack() {
        ProtectionSamplingController controller = new ProtectionSamplingController();
        controller.reset(0L);
        controller.onAmbientResult(
                4_000L,
                ProtectionAmbientController.Action.HOLD,
                "AMBIENT_HYSTERESIS_HOLD",
                false);
        controller.onAmbientResult(
                12_000L,
                ProtectionAmbientController.Action.HOLD,
                "AMBIENT_HYSTERESIS_HOLD",
                false);

        controller.onAmbientResult(
                13_000L,
                ProtectionAmbientController.Action.AMBIENT_BRIGHTENED,
                "FAST_SLOW_BRIGHT_CONFIRMED",
                false);

        assertEquals(ProtectionSamplingController.Mode.FAST_TRACK, controller.getMode());
    }

    @Test
    public void userHoldAndScreenOffUseLowestPowerModes() {
        ProtectionSamplingController controller = new ProtectionSamplingController();
        controller.reset(0L);

        controller.onAmbientResult(
                3_000L,
                ProtectionAmbientController.Action.HOLD,
                "USER_HOLD_ACTIVE",
                true);
        assertEquals(ProtectionSamplingController.Mode.USER_HOLD_ECO, controller.getMode());

        controller.onScreenOff(4_000L);
        assertEquals(ProtectionSamplingController.Mode.SCREEN_OFF_SLEEP, controller.getMode());

        controller.onScreenWake(5_000L);
        assertEquals(ProtectionSamplingController.Mode.FAST_TRACK, controller.getMode());
    }
}
