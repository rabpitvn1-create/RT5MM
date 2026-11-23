package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.*;
import org.junit.Test;

public class ManualHoldPolicyTest {
    @Test public void smallAmbientChangesNeverRelease() {
        ManualHoldPolicy p=new ManualHoldPolicy();
        assertFalse(p.shouldRelease(100f,115f,0L));
        assertFalse(p.shouldRelease(100f,120f,1000L));
        assertFalse(p.shouldRelease(100f,105f,10000L));
    }

    @Test public void meaningfulChangeRequiresDurationAndMultipleSamples() {
        ManualHoldPolicy p=new ManualHoldPolicy();
        assertFalse(p.shouldRelease(100f,160f,1000L));
        assertFalse(p.shouldRelease(100f,160f,1400L));
        assertFalse(p.shouldRelease(100f,160f,1900L));
        assertTrue(p.shouldRelease(100f,160f,2200L));
    }

    @Test public void hysteresisRejectsFlicker() {
        ManualHoldPolicy p=new ManualHoldPolicy();
        assertFalse(p.shouldRelease(100f,180f,0L));
        assertFalse(p.shouldRelease(100f,120f,800L));
        assertFalse(p.shouldRelease(100f,180f,1400L));
        assertFalse(p.shouldRelease(100f,180f,2200L));
        assertTrue(p.shouldRelease(100f,180f,2600L));
    }

    @Test public void screenOffDoesNotInvalidateManualPreference() {
        ManualHoldPolicy p=new ManualHoldPolicy();
        assertFalse(ManualHoldPolicy.shouldReleaseOnScreenOff(true));
        p.reset();
        assertFalse(p.shouldRelease(0f,5f,1000L));
    }

    @Test public void negativeAndUnknownLuxAreNotReleaseEvidence() {
        ManualHoldPolicy p=new ManualHoldPolicy();
        assertFalse(p.shouldRelease(-1f,100f,1000L));
        assertFalse(p.shouldRelease(100f,Float.NaN,2000L));
    }
}
