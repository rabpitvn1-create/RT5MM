# Redmi Screen Protection

A personal one-button Android screen-protection and adaptive-brightness app for Redmi / HyperOS.

The app keeps the system brightness mode under its own control while enabled, observes the ambient-light sensor only while the screen is on, respects manual user changes, and restores the previous Android brightness mode when protection stops.

## Latest APK update

- Version: **1.0.35**
- Release channel: debug APK
- Release tag: `screen-protection-latest`
- APK filename: `Screen-Protection-debug.apk`
- Release note: fixes delayed and stepped brightness behavior by making the newest lux sample effective immediately, allowing guarded fast-only confirmation, shortening ambient horizons, replacing slow raw stepping with short adaptive ramps, and preventing cached lux or service timers from bypassing the ambient controller.

## Active architecture

```text
Light sensor
→ AmbientLightRingBuffer
→ ProtectionAmbientController
→ ProtectionCurveEngine
→ BrightnessDecisionEngine
→ ProtectionTransitionEngine
→ Android system brightness
```

The layers deliberately remain separate:

```text
Sensor lux is an observation.
Accepted ambient lux is environmental state.
Target raw is a desired output.
A brightness write is a controlled action.
```

## Version 1.0.35 response fixes

### Immediate newest-sample weighting

The newest sensor callback now receives a bounded synthetic duration in the ring-buffer calculation. It therefore affects `fastLux` during the same callback instead of waiting for the next sensor event.

### Faster ambient estimates

- Fast horizon: about **700 ms**
- Slow horizon: about **3.2 s**
- Total RAM buffer horizon: about **8 s**
- Strong brightening debounce: about **100 ms**
- Normal brightening debounce: about **350 ms**
- Strong darkening debounce: about **220 ms**
- Normal darkening debounce: about **650 ms**
- Confirmed deep-night entry: about **3 s**

These are app-level starting values and remain subject to device testing.

### Guarded fast-only confirmation

Normal operation still prefers agreement between fast and slow lux. A large, repeated change can now be accepted provisionally from the fast estimator when:

- at least two recent samples cross the active threshold;
- the fast estimate has moved far enough from the accepted ambient anchor;
- the fast estimate is also meaningfully ahead of the slow estimate;
- the change is not an unconfirmed deep-night request.

Slow lux can subsequently correct the accepted state if the environment does not remain changed.

### Indoor hysteresis

Ordinary indoor changes remain inside an asymmetric dead band. The band is narrower than in 1.0.34, so the controller does not freeze for too long and then jump suddenly, while small room-light fluctuations still cause no brightness writes.

### Sunlight fast recovery

After two recent strong-light observations, the controller can make one capped readability rescue before final ambient confirmation:

- very low current raw → rescue up to raw 31;
- low current raw → rescue up to raw 37;
- middle current raw → rescue up to raw 43;
- the final protected target remains capped at raw 49.

The rescue does not blindly jump to maximum brightness.

### Dark fast settle and deep-night guard

After two recent low-light observations, a bright screen can settle rapidly into raw 8–14 for immediate eye comfort. Raw 4–6 remains protected by slow-lux confirmation and the longer deep-night debounce.

### Short adaptive ramps

The old transition engine moved only 1–3 raw every 380–1,100 ms. Version 1.0.35 uses proportional steps and short delays:

- upward steps: up to 8 raw, about 90 ms apart;
- ordinary downward steps: up to 6 raw, about 120 ms apart;
- final deep-night steps: up to 2 raw, about 160 ms apart.

This normally finishes a confirmed transition in a few hundred milliseconds rather than several seconds, while retaining visible continuity.

### No cached-lux brightness writes

The foreground-service timer is now only a health and notification heartbeat. It cannot call the mapping path with persisted lux. Refresh requests only reassert sensor registration.

Screen-on and recovery values are also rejected by the final decision gate until fresh sensor data establishes a new ambient state.

## Deep-night curve

- 0–0.5 lx → raw 4
- 0.5–1.5 lx → raw 5
- 1.5–3 lx → raw 6
- 3–6 lx → raw 7
- up to 10 lx → raw 11

Higher lux ranges interpolate through the protected curve up to raw 49.

## User control

Manual brightness changes enter user hold instead of being immediately overwritten. During hold, sensor observations may continue, but rescue, settle, and confirmed target writes are not applied. A sufficiently large environment change releases the hold and rebuilds fresh ambient history.

## Battery behavior

- Screen off unregisters the light sensor.
- Screen on rebuilds a fresh ambient buffer.
- Sensor history and counters are RAM-first.
- Accepted lux and diagnostics are persisted only when needed.
- Stable indoor observation produces no brightness writes.
- The service heartbeat cannot modify brightness.

## Automated checks

GitHub Actions now runs `:app:testDebugUnitTest` before building or publishing an APK. Initial tests cover:

- immediate influence of the newest lux sample;
- sunlight rescue after repeated strong-light samples;
- safe dark intermediate settle after repeated low-light samples.

## Required HyperOS setup

Grant these manually when prompted:

- Modify system settings
- Notification permission on Android 13+
- Unrestricted battery / no battery optimization
- HyperOS Autostart / No restrictions / Lock in Recents when available

The app remains simple outside and uses a layered state-estimation and control system inside.
