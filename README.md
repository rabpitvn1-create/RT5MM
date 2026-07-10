# Redmi Screen Protection

A personal one-button Android screen-protection and adaptive-brightness app for Redmi / HyperOS.

The app keeps the system brightness mode under its own control while enabled, observes ambient light only while the screen is on, respects manual user changes, and restores the previous Android brightness mode when protection stops.

## Latest APK update

- Version: **1.0.36**
- Release channel: debug APK
- Release tag: `screen-protection-latest`
- APK filename: `Screen-Protection-debug.apk`
- Release note: begins the battery-optimization phase by moving diagnostic logging out of the sensor hot path, reducing periodic service wakeups, limiting brightness transitions to a three-write budget, and adding a tested adaptive sampling policy foundation.

## Active brightness architecture

```text
Light sensor
→ AmbientLightRingBuffer
→ ProtectionAmbientController
→ ProtectionCurveEngine
→ BrightnessDecisionEngine
→ ProtectionTransitionEngine
→ Android system brightness
```

The layers remain separate:

```text
Sensor lux is an observation.
Accepted ambient lux is environmental state.
Target raw is a desired output.
A brightness write is a controlled action.
```

## Response behavior

- Fast lux horizon: about 700 ms.
- Slow lux horizon: about 3.2 seconds.
- Strong brightening debounce: about 100 ms.
- Normal brightening debounce: about 350 ms.
- Strong darkening debounce: about 220 ms.
- Normal darkening debounce: about 650 ms.
- Confirmed deep-night entry: about 3 seconds.
- Sunlight recovery uses a capped intermediate raw rather than blindly jumping to maximum.
- Dark settle can quickly move into raw 8–14 while raw 4–6 remains guarded by slow-lux confirmation.

## Battery optimization phase

### RAM-first diagnostic logging

`BrightnessLogManager` no longer writes SharedPreferences from sensor, decision, and transition hot paths.

- Duplicate suppression stays in RAM.
- Log entries accumulate in a bounded in-memory buffer.
- The buffer is flushed on screen-off, service stop, service destruction, or explicit export.
- Normal duplicate log events are counted without creating a disk write.

### Reduced service wakeups

The foreground service no longer wakes every 30–60 seconds for routine housekeeping.

- Health checkpoint: every 5 minutes.
- Notification fallback refresh: every 15 minutes.
- Real state changes still update the notification immediately.
- Heartbeat timers cannot map cached lux into brightness.

### Three-write transition budget

A normal confirmed brightness transition now uses at most three controlled writes:

```text
current raw
→ coarse response
→ refinement
→ final target
```

A sunlight rescue or dark settle counts as the first intermediate action; when the later confirmed target matches that sequence, only two additional transition writes are budgeted.

Small changes can complete in one write. Retargeting to the same active target does not restart the transition.

### Adaptive sampling policy foundation

`ProtectionSamplingController` defines the intended power modes:

- `FAST_TRACK`: 100 ms sampling after wake or meaningful ambient change.
- `ACTIVE_TRACK`: 300 ms sampling while the environment is still evolving.
- `STABLE_ECO`: 850 ms sampling after sustained hysteresis hold.
- `USER_HOLD_ECO`: 1.5 second sampling while manual brightness hold is active.
- `SCREEN_OFF_SLEEP`: sensor off.

The policy includes minimum mode dwell time so the app does not waste energy repeatedly unregistering and registering the sensor. Unit tests cover stable-to-eco transitions, immediate return to fast tracking after a real light change, user-hold eco mode, and screen-off sleep.

The sampling policy is deliberately separated from Android sensor registration so it can be verified independently before being connected to the active manager path.

## Deep-night curve

- 0–0.5 lx → raw 4
- 0.5–1.5 lx → raw 5
- 1.5–3 lx → raw 6
- 3–6 lx → raw 7
- up to 10 lx → raw 11

Higher ranges interpolate through the protected curve up to raw 49.

## User control

Manual brightness changes enter user hold instead of being immediately overwritten. During hold, rescue, settle, and confirmed target writes are blocked. A sufficiently large environment change releases the hold and rebuilds fresh ambient history.

## Current battery behavior

- Screen off unregisters the light sensor.
- Screen on rebuilds a fresh ambient buffer.
- Sensor history and battery counters are RAM-first.
- Brightness transitions have a bounded write budget.
- Stable indoor observation produces no brightness writes.
- The service heartbeat cannot modify brightness.
- Diagnostic logs are no longer persisted on every event.

## Automated checks

GitHub Actions runs `:app:testDebugUnitTest` before building or publishing an APK. Tests now cover:

- immediate influence of the newest lux sample;
- sunlight rescue after repeated strong-light samples;
- safe dark intermediate settle;
- zero-timestamp warmup regression;
- adaptive sampling state transitions;
- three-write transition budget calculations.

## Required HyperOS setup

Grant these manually when prompted:

- Modify system settings
- Notification permission on Android 13+
- Unrestricted battery / no battery optimization
- HyperOS Autostart / No restrictions / Lock in Recents when available

The app remains simple outside and uses a layered state-estimation, power-management, and control system inside.
