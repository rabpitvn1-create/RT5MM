package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtectionTransitionEngineBudgetTest {
    @Test
    public void largeUpwardTransitionReachesTargetWithinThreeWrites() {
        int current = 7;
        int target = 49;

        int first = ProtectionTransitionEngine.calculateBudgetedNextRaw(current, target, 3);
        int second = ProtectionTransitionEngine.calculateBudgetedNextRaw(first, target, 2);
        int third = ProtectionTransitionEngine.calculateBudgetedNextRaw(second, target, 1);

        assertTrue(first > current && first < target);
        assertTrue(second > first && second < target);
        assertEquals(target, third);
    }

    @Test
    public void largeDownwardTransitionReachesTargetWithinThreeWrites() {
        int current = 49;
        int target = 4;

        int first = ProtectionTransitionEngine.calculateBudgetedNextRaw(current, target, 3);
        int second = ProtectionTransitionEngine.calculateBudgetedNextRaw(first, target, 2);
        int third = ProtectionTransitionEngine.calculateBudgetedNextRaw(second, target, 1);

        assertTrue(first < current && first > target);
        assertTrue(second < first && second > target);
        assertEquals(target, third);
    }

    @Test
    public void smallTransitionUsesSingleFinalWrite() {
        assertEquals(21, ProtectionTransitionEngine.calculateBudgetedNextRaw(20, 21, 3));
    }
}
