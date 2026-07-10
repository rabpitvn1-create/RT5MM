# Redmi Screen Protection

A personal one-button Android screen protection app for Redmi / HyperOS.

The app is not intended to replace every part of Android's display stack. Its job is to keep the screen inside conservative brightness levels, react quickly when readability or eye comfort is at risk, respect manual brightness changes, and remain stable in ordinary indoor lighting.

## Latest APK update

- Version: 1.0.34
- Release channel: debug APK
- Release tag: `screen-protection-latest`
- APK filename: `Screen-Protection-debug.apk`
- Release note: replaces sample-to-raw decision logic with an AOSP-inspired ambient-light controller using a timestamped ring buffer, fast and slow lux, hysteresis, asymmetric debounce, sunlight recovery, and dark fast settle.

## Product direction

- One primary button: turn protection on or off.
- Permission and HyperOS setup remain support flows, not the main interface.
- The system auto-brightness mode is disabled while protection owns brightness and restored when protection stops.
- Manual brightness changes enter user hold instead of being immediately overwritten.
- The light sensor runs only while the screen is on.

## AOSP-inspired control architecture

Version 1.0.34 separates the brightness pipeline into distinct layers:

```text
Sensor samples
→ AmbientLightRingBuffer
→ ProtectionAmbientController
→ ProtectionCurveEngine
→ BrightnessDecisionEngine
→ ProtectionTransitionEngine
→ Android system brightness
```

The important rule is:

```text
Sensor lux is not ambient state.
Ambient state is not a brightness write.
```

### `AmbientLightRingBuffer`

The app now stores timestamped `(time, lux)` observations in RAM instead of relying on a fixed number of samples.

- Old samples are pruned by time horizon.
- One boundary sample is retained during pruning so weighted averages remain continuous.
- Fast and slow lux are calculated using duration weighting with a controlled recency preference.
- SharedPreferences is not used as a sensor hot-path buffer.

### `ProtectionAmbientController`

The ambient controller owns environmental state estimation.

- Fast horizon: approximately 1.2 seconds.
- Slow horizon: approximately 6 seconds.
- Buffer horizon: approximately 10 seconds.
- Brightening and darkening use separate debounce timing.
- Deep-night entry uses the longest confirmation.
- Accepted ambient lux becomes an anchor with brightening and darkening thresholds around it.

The controller emits:

- `HOLD`: remain inside the current ambient hysteresis band.
- `INITIALIZED`: a valid ambient anchor has been established.
- `AMBIENT_BRIGHTENED`: fast and slow lux confirm a brighter environment.
- `AMBIENT_DARKENED`: fast and slow lux confirm a darker environment.
- `SUNLIGHT_RESCUE`: move immediately to a capped readable raw while final ambient confirmation continues.
- `DARK_SETTLE`: move immediately to a safe dim intermediate raw while deep-night confirmation continues.

## Indoor stability

The app no longer tries to follow every indoor lux fluctuation.

Indoor anchors use a deliberately wide hysteresis band. When fast and slow lux remain between the darkening and brightening thresholds:

```text
ambient anchor stays unchanged
→ target raw stays unchanged
→ brightness write count stays at zero
```

This replaces the earlier idea of pausing the light sensor. The app keeps observing while avoiding unnecessary writes.

## Sunlight Fast Recovery

Sudden movement into strong light has an upward-only fast path.

Requirements include:

- at least two recent high-light observations;
- a large target-raw increase;
- a high outdoor target region;
- no active user hold.

The rescue write is intentionally capped rather than jumping directly to maximum:

- very low current raw can rescue to raw 25;
- low-mid current raw can rescue to raw 31;
- mid current raw can rescue to raw 34;
- final target still requires accepted ambient confirmation.

## Dark Fast Settle and Deep Night Guard

A sudden move into darkness can immediately settle to a safe intermediate range of raw 7–11 when multiple recent low-light observations agree.

This is separate from final deep-night entry:

- raw 7–11 can provide quick eye comfort;
- raw 4–6 still requires slow-lux confirmation and a longer debounce;
- a short sensor cover is not allowed to force raw 4 immediately.

## Brightness Decision Gate

`BrightnessDecisionEngine` is now a final screen-raw gate rather than an environmental estimator.

It receives only accepted ambient states and decides:

- `NOOP`: the target remains inside screen raw hysteresis;
- `WAIT`: a forced stale deep-night value must wait for fresh ambient confirmation;
- `APPLY`: the accepted ambient state maps to a meaningful new target raw.

This removes duplicated filtering between ambient estimation and raw decisions.

## Deep-night raw curve

- 0 to 0.5 lx → raw 4
- 0.5 to 1.5 lx → raw 5
- 1.5 to 3 lx → raw 6
- 3 to 6 lx → raw 7
- up to 10 lx → raw 11

Higher ranges continue through the protected interpolation curve up to raw 49.

## Current protection buckets

- 5% = raw 4
- 8% = raw 5
- 10% = raw 6
- 12% = raw 7
- 15% = raw 8
- 18% = raw 10
- 20% = raw 11
- 23% = raw 13
- 25% = raw 14
- 28% = raw 16
- 30% = raw 17
- 33% = raw 19
- 35% = raw 21
- 38% = raw 23
- 40% = raw 25
- 43% = raw 28
- 45% = raw 31
- 48% = raw 34
- 50% = raw 37
- 53% = raw 40
- 55% = raw 43
- 58% = raw 46
- 60% = raw 49

These percentages are app-level protection buckets, not guaranteed HyperOS UI percentages.

## Battery-aware protection

- Screen off unregisters the light sensor and suspends interval evaluation.
- Screen on rebuilds fresh ambient history instead of trusting stale sensor state.
- Ring-buffer and controller state remain in RAM.
- Accepted ambient lux is persisted only when needed.
- Diagnostic data is throttled.
- Brightness writes occur only after ambient transition, rescue, settle, or meaningful raw change.
- The foreground service and brightness observer remain active only while protection is enabled.

## Diagnostic mode

Diagnostics now expose:

- accepted ambient lux;
- fast lux;
- slow lux;
- darkening and brightening thresholds;
- ambient action and reason;
- current and target raw;
- decision reason and confidence;
- battery and learning counters.

## Required setup

For reliable operation on HyperOS, grant manually when prompted:

- Modify system settings
- Notification permission on Android 13+
- Unrestricted battery / no battery optimization
- HyperOS Autostart / No restrictions / Lock in Recents when available

The app should remain simple outside and operate as a layered control system inside.
