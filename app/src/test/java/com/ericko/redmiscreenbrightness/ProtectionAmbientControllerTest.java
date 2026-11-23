package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.*;
import org.junit.Test;

public class ProtectionAmbientControllerTest {
    @Test public void suddenStrongLightCreatesReadableRescue() {
        ProtectionAmbientController c=new ProtectionAmbientController();
        long t=10000L;
        c.onLuxSample(t,10f,10);
        c.onLuxSample(t+200,10f,10);
        c.onLuxSample(t+400,1000f,10);
        ProtectionAmbientController.Result r=c.onLuxSample(t+600,1200f,10);
        assertEquals(ProtectionAmbientController.Action.SUNLIGHT_RESCUE,r.action);
        assertTrue(r.intermediateRaw>10 && r.intermediateRaw<=r.finalTargetRaw);
    }
    @Test public void suddenDarknessCreatesIntermediateSettle() {
        ProtectionAmbientController c=new ProtectionAmbientController();
        long t=10000L;
        c.onLuxSample(t,1000f,43);
        c.onLuxSample(t+200,1000f,43);
        c.onLuxSample(t+400,10f,43);
        ProtectionAmbientController.Result r=c.onLuxSample(t+600,8f,43);
        assertEquals(ProtectionAmbientController.Action.DARK_SETTLE,r.action);
        assertTrue(r.intermediateRaw>=11 && r.intermediateRaw<=17);
        assertTrue(r.intermediateRaw<43);
    }
    @Test public void normalWarmupDoesNotRestartAtZero() {
        ProtectionAmbientController c=new ProtectionAmbientController();
        assertEquals(ProtectionAmbientController.Action.HOLD,c.onLuxSample(0L,10f,10).action);
        assertEquals(ProtectionAmbientController.Action.INITIALIZED,
                c.onLuxSample(200L,10f,10).action);
    }
    @Test public void smallLuxChangeDoesNotTriggerNewBrightnessDecision() {
        ProtectionAmbientController c=new ProtectionAmbientController();
        c.onLuxSample(1000L,100f,22);
        c.onLuxSample(1200L,100f,22);
        for(int i=0;i<6;i++) {
            ProtectionAmbientController.Result r=c.onLuxSample(1500L+i*200L,
                    102f+i%2,22);
            assertEquals(ProtectionAmbientController.Action.HOLD,r.action);
        }
    }
    @Test public void screenWakeRequiresFreshSamples() {
        ProtectionAmbientController c=new ProtectionAmbientController();
        c.onLuxSample(1000L,100f,22);
        c.onLuxSample(1200L,100f,22);
        c.reset();
        assertEquals(ProtectionAmbientController.Action.HOLD,
                c.onLuxSample(2000L,1000f,22).action);
    }
}
