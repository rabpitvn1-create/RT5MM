package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.*;
import org.junit.Test;

public class ProtectionSamplingControllerTest {
    @Test public void stableAmbientMovesToEco() {
        ProtectionSamplingController c=new ProtectionSamplingController();
        c.reset(0L);
        c.onAmbientResult(4000L,ProtectionAmbientController.Action.HOLD,"AMBIENT_HYSTERESIS_HOLD",false);
        assertEquals(ProtectionSamplingController.Mode.ACTIVE_TRACK,c.getMode());
        c.onAmbientResult(9500L,ProtectionAmbientController.Action.HOLD,"AMBIENT_HYSTERESIS_HOLD",false);
        assertEquals(ProtectionSamplingController.Mode.STABLE_ECO,c.getMode());
    }
    @Test public void suddenChangeRestoresFastTrack() {
        ProtectionSamplingController c=new ProtectionSamplingController();
        c.reset(0L);
        c.onAmbientResult(2000L,ProtectionAmbientController.Action.HOLD,"AMBIENT_HYSTERESIS_HOLD",false);
        c.onAmbientResult(8000L,ProtectionAmbientController.Action.HOLD,"AMBIENT_HYSTERESIS_HOLD",false);
        c.onAmbientResult(8500L,ProtectionAmbientController.Action.AMBIENT_BRIGHTENED,"FAST_SLOW_BRIGHT_CONFIRMED",false);
        assertEquals(ProtectionSamplingController.Mode.FAST_TRACK,c.getMode());
    }
    @Test public void userHoldProcessesLuxWithoutUsageAccess() {
        ProtectionSamplingController c=new ProtectionSamplingController();
        c.reset(0L);
        c.onAmbientResult(2000L,ProtectionAmbientController.Action.HOLD,"USER_HOLD",true);
        assertEquals(ProtectionSamplingController.Mode.USER_HOLD_ECO,c.getMode());
        assertTrue(c.shouldProcessSample(2000L));
        assertFalse(c.shouldProcessSample(2300L));
        assertTrue(c.shouldProcessSample(2600L));
    }
    @Test public void sleepAndWakeResetSampling() {
        ProtectionSamplingController c=new ProtectionSamplingController();
        c.reset(1000L);
        assertTrue(c.shouldProcessSample(1000L));
        c.onScreenOff(1050L);
        assertFalse(c.shouldProcessSample(1100L));
        c.onScreenWake(2000L);
        assertEquals(ProtectionSamplingController.Mode.FAST_TRACK,c.getMode());
        assertTrue(c.shouldProcessSample(2000L));
    }
    @Test public void batchingIsLimitedToStableEco() {
        assertEquals(0,ProtectionSamplingController.Mode.ACTIVE_TRACK.maxReportLatencyUs);
        assertEquals(0,ProtectionSamplingController.Mode.USER_HOLD_ECO.maxReportLatencyUs);
        assertEquals(500000,ProtectionSamplingController.Mode.STABLE_ECO.maxReportLatencyUs);
    }
}
