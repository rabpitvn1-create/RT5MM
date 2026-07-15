# Screen Protection 2.1.1

A one-button adaptive-brightness controller built specifically for Redmi / HyperOS.

The app does not reuse Android's automatic-brightness output. While enabled, it temporarily takes ownership of system brightness, reads the physical ambient-light sensor, estimates a stable environmental state, maps that state onto a conservative Redmi raw-brightness curve, and restores the previous Android brightness mode when protection is turned off.

## Release

- Version: **2.1.1**
- Package: `com.ericko.redmiscreenbrightness`
- Minimum Android: 7.0 / API 24
- Target Android: API 35
- Release tag: `screen-protection-latest`
- APK: `Screen-Protection-debug.apk`

## Control pipeline

```text
Physical light sensor
→ timestamped ambient ring buffer
→ fast and slow lux estimates
→ hysteresis + asymmetric debounce
→ occlusion and deep-night guards
→ calibrated lux-to-raw curve
→ raw write hysteresis
→ bounded transition engine
→ Android system brightness
```

The data types stay separate:

```text
Sensor lux        = observation
Accepted lux      = environmental state
Target raw        = desired output
Brightness write  = controlled side effect
```

Cached lux is diagnostic-only. Screen wake, process recovery and manual refresh always rebuild fresh sensor history before changing brightness.

## Original raw calibration retained

The verified Redmi anchors are permanent regression-tested invariants:

| Display level | System raw |
|---:|---:|
| 20% | 11 |
| 30% | 17 |
| 40% | 26 |
| 50% | 38 |
| 60% | 49 |

Intermediate values use monotonic logarithmic interpolation. The protected curve is clamped to raw **4–49**.

## Ambient estimator

- Fast horizon: approximately 700 ms.
- Slow horizon: approximately 3.2 seconds.
- Brightening is faster than darkening.
- Strong sunlight has a guarded readable intermediate step.
- Sudden darkness has a safe intermediate settle.
- Raw 4–6 requires confirmed deep-night evidence.
- A dedicated occlusion guard rejects a brief hand or pocket cover after a bright environment.
- Hysteresis prevents repeated writes near a threshold.

## Adaptive sampling is active

The sampling policy is connected directly to Android sensor registration:

- `FAST_TRACK`: 100 ms after wake or a meaningful ambient transition.
- `ACTIVE_TRACK`: 400 ms while the environment is evolving.
- `STABLE_ECO`: 1.5 seconds after sustained stability.
- `USER_HOLD_ECO`: 3 seconds while respecting a manual user brightness choice.
- `SCREEN_OFF_SLEEP`: sensor completely unregistered.

Minimum mode dwell time prevents register/unregister thrashing. An in-process
software gate also enforces these intervals when a vendor sensor HAL delivers
callbacks faster than the requested Android rate.

## Manual user intent

A real external brightness change is distinguished from an app write by matching the exact raw value and a short observer window. A stable manual change enters user hold rather than being overwritten.

- With Usage Access granted, the chosen level remains unchanged for as long as the same app stays in the foreground.
- Moving to another app releases the hold within the low-power three-second check interval and rebuilds fresh ambient history.
- Turning the screen off always releases the hold; turning it back on resumes protection from fresh sensor data.
- Without Usage Access, the safe fallback is to keep the chosen level until the screen turns off.
- Android SystemUI is ignored when identifying the foreground app, so opening the notification shade to move the brightness slider does not immediately release the hold.
- Lux changes never expire a manual hold while its app session is still active.

## Battery behavior

- The light sensor is off whenever the display is off.
- Sampling slows automatically in stable conditions.
- System brightness is observer/write cached instead of querying SettingsProvider on every sensor sample.
- Diagnostic logging is RAM-first and bounded.
- Diagnostics are persisted at most once per minute during operation.
- Notification and lifecycle health updates are event-driven; there are no periodic background wakeups.
- Normal transitions use at most three writes.
- Duplicate targets and raw differences of one step are skipped.
- SharedPreferences writes are throttled and reserved for meaningful state.

## App experience

The main screen exposes one primary protection switch, live environment and brightness state, required setup, optional reliability recommendations, and expandable diagnostics.

Required:

- Modify system settings.
- A hardware ambient-light sensor.

Recommended on HyperOS:

- Usage Access for app-aware manual brightness hold.
- Notification permission on Android 13+.
- Review battery optimization settings if HyperOS stops the service.
- Autostart / No restrictions / Lock in Recents when available.

Notification and battery recommendations improve persistence but do not incorrectly block startup.

The interface, foreground notification, Quick Settings tile and diagnostics are
available in both English and Vietnamese through Android locale resources.

## Quick Settings and recovery

- Quick Settings tile toggles protection directly.
- Boot and package replacement restore protection only when it was already enabled.
- The foreground service sleeps with the screen off and resumes with fresh ambient history.
- The previous Android brightness mode is restored when protection stops.

## Automated verification

GitHub Actions runs unit tests before every APK build. Coverage includes:

- weighted ambient history;
- newest-sample influence;
- sunlight rescue;
- safe dark settle;
- zero-timestamp warmup;
- adaptive sampling state transitions and anti-thrash behavior;
- manual hold retention, app-change release and screen-off fallback policy;
- bounded transition calculations;
- monotonic lux-to-raw output;
- immutable original Redmi raw anchors.

The main workflow publishes the latest successful `main` APK under the `screen-protection-latest` release tag.
