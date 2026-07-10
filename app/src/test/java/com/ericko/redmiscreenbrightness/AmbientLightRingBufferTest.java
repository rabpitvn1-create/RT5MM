package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AmbientLightRingBufferTest {
    @Test
    public void latestSampleAffectsEstimateImmediately() {
        AmbientLightRingBuffer buffer = new AmbientLightRingBuffer();
        buffer.push(0L, 10f);
        buffer.push(200L, 10f);

        float before = buffer.calculateWeightedLux(200L, 700L);

        buffer.push(400L, 1000f);
        float after = buffer.calculateWeightedLux(400L, 700L);

        assertTrue("Latest sample should immediately raise the fast estimate", after > before + 100f);
    }
}
