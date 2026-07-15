package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ManualHoldPolicyTest {
    @Test
    public void sameForegroundAppKeepsManualBrightness() {
        assertFalse(ManualHoldPolicy.shouldReleaseForForeground(
                "com.example.reader", "com.example.reader"));
    }

    @Test
    public void changingForegroundAppReleasesManualBrightness() {
        assertTrue(ManualHoldPolicy.shouldReleaseForForeground(
                "com.example.reader", "com.example.camera"));
    }

    @Test
    public void missingUsageDataFallsBackToScreenOffRelease() {
        assertFalse(ManualHoldPolicy.shouldReleaseForForeground("", "com.example.camera"));
        assertFalse(ManualHoldPolicy.shouldReleaseForForeground("com.example.reader", null));
        assertTrue(ManualHoldPolicy.shouldReleaseOnScreenOff(true));
        assertFalse(ManualHoldPolicy.shouldReleaseOnScreenOff(false));
    }
}
