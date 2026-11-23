package com.ericko.redmiscreenbrightness;

import static org.junit.Assert.*;
import org.junit.Test;

public class ObsidianUiStateTest {
    private ObsidianUiState.Phase state(boolean on, boolean permission,
            boolean sensor, boolean restorePending, boolean service, boolean manual,
            boolean registered, boolean fresh, boolean hold, boolean grace) {
        return ObsidianUiState.resolve(on, permission, sensor, restorePending,
                service, manual, registered, fresh, hold, grace);
    }

    @Test public void offIsNotConfusedWithForegroundActivity() {
        assertEquals(ObsidianUiState.Phase.OFF,
                state(false,true,true,false,true,true,true,true,false,false));
    }
    @Test public void missingPermissionHasAnActionableSingleButtonState() {
        assertEquals(ObsidianUiState.Phase.PERMISSION_REQUIRED,
                state(false,false,true,false,false,false,false,false,false,false));
    }
    @Test public void startIsNotPrematurelyCalledProtecting() {
        assertEquals(ObsidianUiState.Phase.STARTING,
                state(true,true,true,false,false,false,false,false,false,true));
    }
    @Test public void protectingRequiresForegroundManualSensorAndFreshData() {
        assertEquals(ObsidianUiState.Phase.PROTECTING,
                state(true,true,true,false,true,true,true,true,false,false));
        assertEquals(ObsidianUiState.Phase.SERVICE_ERROR,
                state(true,true,true,false,false,true,true,true,false,false));
        assertEquals(ObsidianUiState.Phase.SERVICE_ERROR,
                state(true,true,true,false,true,false,true,true,false,false));
        assertEquals(ObsidianUiState.Phase.SERVICE_ERROR,
                state(true,true,true,false,true,true,false,true,false,false));
    }
    @Test public void manualHoldIsNotNormalAutomaticProtection() {
        assertEquals(ObsidianUiState.Phase.USER_HOLD,
                state(true,true,true,false,true,true,true,true,true,false));
    }
    @Test public void staleLuxIsHiddenNotDisplayedAsCurrent() {
        ObsidianUiState.Phase state=state(true,true,true,false,true,true,true,false,true,false);
        assertEquals(ObsidianUiState.Phase.WAITING_SENSOR,state);
        assertFalse(ObsidianUiState.canShowMetrics(state));
    }
    @Test public void stoppedServiceAfterGraceIsExplicitError() {
        assertEquals(ObsidianUiState.Phase.SERVICE_ERROR,
                state(true,true,true,false,false,false,false,false,false,false));
    }
    @Test public void failedModeRestorationOutranksOff() {
        assertEquals(ObsidianUiState.Phase.RESTORE_FAILED,
                state(false,true,true,true,false,false,false,false,false,false));
    }
    @Test public void lightBandsOnlyDescribeSensorLux() {
        assertEquals(ObsidianUiState.Ambient.UNKNOWN,ObsidianUiState.ambient(Float.NaN));
        assertEquals(ObsidianUiState.Ambient.UNKNOWN,ObsidianUiState.ambient(-1));
        assertEquals(ObsidianUiState.Ambient.LOW,ObsidianUiState.ambient(70));
        assertEquals(ObsidianUiState.Ambient.MEDIUM,ObsidianUiState.ambient(70.01f));
        assertEquals(ObsidianUiState.Ambient.MEDIUM,ObsidianUiState.ambient(800));
        assertEquals(ObsidianUiState.Ambient.HIGH,ObsidianUiState.ambient(800.1f));
    }
}
