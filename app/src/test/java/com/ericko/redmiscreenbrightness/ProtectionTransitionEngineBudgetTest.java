package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtectionTransitionEngineBudgetTest {
    @Test
    public void largeUpwardTransitionReachesTargetWithinThreeWrites() {
        int current = 10;
        int target = 52;

        int first = ProtectionTransitionEngine.calculateBudgetedNextRaw(current, target, 3);
        int second = ProtectionTransitionEngine.calculateBudgetedNextRaw(first, target, 2);
        int third = ProtectionTransitionEngine.calculateBudgetedNextRaw(second, target, 1);

        assertTrue(first > current && first < target);
        assertTrue(second > first && second < target);
        assertEquals(target, third);
    }

    @Test
    public void largeDownwardTransitionReachesTargetWithinThreeWrites() {
        int current = 52;
        int target = 7;

        int first = ProtectionTransitionEngine.calculateBudgetedNextRaw(current, target, 3);
        int second = ProtectionTransitionEngine.calculateBudgetedNextRaw(first, target, 2);
        int third = ProtectionTransitionEngine.calculateBudgetedNextRaw(second, target, 1);

        assertTrue(first < current && first > target);
        assertTrue(second < first && second > target);
        assertEquals(target, third);
    }

    @Test
    public void smallTransitionUsesSingleFinalWrite() {
        assertEquals(24, ProtectionTransitionEngine.calculateBudgetedNextRaw(23, 24, 3));
    }
}
