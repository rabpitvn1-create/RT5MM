package com.ericko.redmiscreenbrightness;

import static com.ericko.redmiscreenbrightness.ObsidianUiState.Phase.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class ObsidianUiStateTest {
    @Test public void offIsNotInferredFromAnUnconfirmedRequestedSession() {
        assertEquals(OFF, ObsidianUiState.resolve(
                false,true,true,false,false,false,false,false,false,false));
        assertEquals(UNCONFIRMED, ObsidianUiState.resolve(
                true,true,true,false,false,false,false,false,false,false));
    }

    @Test public void recoveryAndPendingStopTakePriorityOverPermission() {
        assertEquals(RESTORE_FAILED, ObsidianUiState.resolve(
                false,false,true,true,true,false,false,false,false,true));
        assertEquals(STOPPING, ObsidianUiState.resolve(
                false,false,true,false,true,false,false,false,false,false));
        assertEquals(PERMISSION_REQUIRED, ObsidianUiState.resolve(
                true,false,true,false,false,true,true,false,true,false));
    }

    @Test public void startingAndMeasuringAreNeutralUntilControlIsConfirmed() {
        assertEquals(STARTING, ObsidianUiState.resolve(
                true,true,true,false,false,true,false,false,true,false));
        assertEquals(WAITING_SENSOR, ObsidianUiState.resolve(
                true,true,true,false,false,false,false,false,true,false));
        assertFalse(ObsidianUiState.isControlling(WAITING_SENSOR));
        assertFalse(ObsidianUiState.isControlling(UNCONFIRMED));
    }

    @Test public void waitingForLuxDoesNotReplaceConfirmedControl() {
        assertEquals(PROTECTING, ObsidianUiState.resolve(
                true,true,true,false,false,false,true,false,true,false));
        assertEquals(USER_HOLD, ObsidianUiState.resolve(
                true,true,true,false,false,false,true,true,true,false));
        assertTrue(ObsidianUiState.isControlling(PROTECTING));
        assertTrue(ObsidianUiState.isControlling(USER_HOLD));
    }

    @Test public void anObservedFailureIsDifferentFromMissingConfirmation() {
        assertEquals(SERVICE_ERROR, ObsidianUiState.resolve(
                true,true,true,false,false,false,false,false,false,true));
        assertEquals(SERVICE_ERROR, ObsidianUiState.resolve(
                false,true,false,false,false,false,false,false,false,false));
    }

    @Test public void lightBandsOnlyDescribeSensorLux() {
        assertEquals(ObsidianUiState.Ambient.UNKNOWN, ObsidianUiState.ambient(Float.NaN));
        assertEquals(ObsidianUiState.Ambient.UNKNOWN, ObsidianUiState.ambient(-1));
        assertEquals(ObsidianUiState.Ambient.LOW, ObsidianUiState.ambient(70));
        assertEquals(ObsidianUiState.Ambient.MEDIUM, ObsidianUiState.ambient(70.01f));
        assertEquals(ObsidianUiState.Ambient.MEDIUM, ObsidianUiState.ambient(800));
        assertEquals(ObsidianUiState.Ambient.HIGH, ObsidianUiState.ambient(800.1f));
    }
}
