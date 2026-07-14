package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
                13_000L,
                ProtectionAmbientController.Action.HOLD,
                "AMBIENT_HYSTERESIS_HOLD",
                false);
        assertEquals(ProtectionSamplingController.Mode.STABLE_ECO, controller.getMode());
    }

    @Test
    public void meaningfulChangeImmediatelyReturnsToFastTrack() {
        ProtectionSamplingController controller = new ProtectionSamplingController();
        controller.reset(0L);
        controller.onAmbientResult(
                4_000L,
                ProtectionAmbientController.Action.HOLD,
                "AMBIENT_HYSTERESIS_HOLD",
                false);
        controller.onAmbientResult(
                13_000L,
                ProtectionAmbientController.Action.HOLD,
                "AMBIENT_HYSTERESIS_HOLD",
                false);

        controller.onAmbientResult(
                14_000L,
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

    @Test
    public void reregistrationPolicyAvoidsModeThrash() {
        ProtectionSamplingController controller = new ProtectionSamplingController();
        controller.reset(1_000L);

        assertFalse(controller.shouldReregister(
                ProtectionSamplingController.Mode.ACTIVE_TRACK, 2_000L));
        assertTrue(controller.shouldReregister(
                ProtectionSamplingController.Mode.ACTIVE_TRACK, 4_000L));
        assertTrue(controller.shouldReregister(
                ProtectionSamplingController.Mode.SCREEN_OFF_SLEEP, 1_100L));
    }

    @Test
    public void softwareGateRejectsVendorCallbacksFasterThanRequested() {
        ProtectionSamplingController controller = new ProtectionSamplingController();
        controller.reset(0L);

        assertTrue(controller.shouldProcessSample(0L));
        assertFalse(controller.shouldProcessSample(50L));
        assertTrue(controller.shouldProcessSample(100L));

        controller.onAmbientResult(
                3_000L,
                ProtectionAmbientController.Action.HOLD,
                "AMBIENT_HYSTERESIS_HOLD",
                false);
        assertEquals(ProtectionSamplingController.Mode.ACTIVE_TRACK, controller.getMode());
        assertFalse(controller.shouldProcessSample(300L));
        assertTrue(controller.shouldProcessSample(500L));
    }

    @Test
    public void screenWakeResetsSoftwareGateForImmediateFreshSample() {
        ProtectionSamplingController controller = new ProtectionSamplingController();
        controller.reset(1_000L);
        assertTrue(controller.shouldProcessSample(1_000L));

        controller.onScreenOff(1_050L);
        assertFalse(controller.shouldProcessSample(1_100L));

        controller.onScreenWake(2_000L);
        assertTrue(controller.shouldProcessSample(2_000L));
    }
}
