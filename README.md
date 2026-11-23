# Screen Protection 2.1.5 Adaptive Core (debug)

**Repository:** RT5MM, the original Screen Protection app for Redmi / HyperOS.
**Package:** `com.ericko.redmiscreenbrightness`. **Minimum:** Android 7 / API 24.
**Target:** Android API 35. **Release:** `screen-protection-v2.1.5-adaptive-core-debug`.

This build is an **internal experimental adaptation for Redmi K90 on HyperOS 4**, not a certified or device-tested brightness-calibration release.

## One-button operation

The main screen has **exactly one button**: ON or OFF. ON verifies that the ambient-light sensor exists and launches Android's Modify system settings permission when required. Before the custom controller writes brightness, Screen Protection saves the original `SCREEN_BRIGHTNESS_MODE` and switches the mode to **MANUAL**, verifying the change. OFF disables control and restores the previously saved Android brightness mode, including AUTOMATIC if that was previously enabled. Android 13+ notification permission is requested during the same activation flow. There are no separate in-app permission/diagnostics buttons and no extra Quick Settings toggle.

The app uses Android's `TYPE_LIGHT` sensor, **not** a claimed Xiaomi historic auto-brightness curve. It does not read HyperOS's proprietary learned history or change the physical panel hardware directly.

## Brightness and K90 caution

The original RT5MM low-brightness curve is retained with **raw 7–52** as its protection band. Display-label points include 20% = raw 14, 30% = raw 20, 40% = raw 29, 50% = raw 41 and 60% = raw 52. The related manual HyperBrightnessWidget reference uses 30% = raw 17, optional raw 19 for comfort, and 60% = raw 49.

**While the controller is enabled, its writes are capped to raw 52. A detected external manual slider change above raw 52 is also clamped back to 52, and that clamped value becomes the new manual hold. There may still be a brief overshoot before Android delivers the settings observer callback; a non-privileged app cannot guarantee a physical hard cap across other system actors.**

**These raw-to-slider mappings have NOT been measured on the Redmi K90.** A 60% app label does not prove that the Xiaomi system slider is at 60% or that physical luminance is limited to 60% of full display output. Check raw 17/19/49/52 and corresponding system slider positions on the K90 before treating the cap as verified. Strong sunlight/readability or HBM is not guaranteed by a low cap.

## Adaptive Core changes since 2.1.4

- Ambient fast/slow windows **500/1900 ms** (was 700/3200 ms); normal brighten/darken debounces **180/420 ms** (was 350/650 ms). Other confirmation delays are reduced but not removed. Pocket/occlusion guard now **900 ms** (was 1600 ms).
- **Manual brightness changes are held until a confirmed meaningful change in ambient lux**, not until the foreground app changes. The gate starts when the absolute lux delta reaches `max(12 lux, 45% of anchor)`, stays pending with hysteresis down to `max(7 lux, 22% of anchor)`, and releases after **at least 1100 ms with two independent samples**. Sleep/wake preserves manual intent, but resets the ambient estimator and pending confirmation; only fresh sensor input may release a hold.
- No Usage Access or app foreground tracking is needed. The obsolete `ForegroundAppTracker` and brightness Quick Settings toggle are removed.
- Eco sampling after about **5 seconds of stable lux**, with 2-second `STABLE_ECO` and optional 500-ms sensor batching; active 350-ms, fast 100-ms during transitions, and manual hold at 600-ms so it can detect environmental changes. Screen off unregisters the sensor. Actual sensor callbacks are controlled by vendor HAL and may differ.
- `RT5MM-Latency` logs the measured elapsed milliseconds between a significant sensor event and a **successful Settings write**. It does not measure when the panel reaches its physical luminance. The latest recorded value is displayed as text in the one-button app.
- Additional write gating prevents a queued brightness transition from writing after OFF. Mode restore and switch now require read-back confirmation.

## Limitations and safety

The absolute raw values and even the raw scale are device- and firmware-dependent. The single button cannot silently grant `WRITE_SETTINGS`. On HyperOS, foreground services can still be killed or restricted; unlocking battery restrictions or autostart requires manual OEM settings when applicable. If HyperOS force-kills the process, Android can remain in MANUAL until RT5MM is reopened and turned OFF or the saved mode is restored. Android system permissions are not a guarantee against force-stop. No root, Magisk, Shizuku, ADB helper, overlay, Extra Dim, or third-party runtime helper is used.

A foreground service remains active while the screen is on; reduced polling **is a hypothesis about battery efficiency**, not a measured saving.

## Acceptance checklist for Redmi K90

1. Verify ON sets `SCREEN_BRIGHTNESS_MODE=MANUAL`, OFF returns to the precise prior mode.
2. Test dark-to-bright and bright-to-dark reaction using repeatable light conditions and inspect the latency readout. Watch for flicker under small lux fluctuations.
3. Adjust system brightness manually, move through mild light fluctuations, then move to a much brighter or darker place for at least 1.1s: the held level must release only for sustained meaningful change, including across screen off/on.
4. Compare baseline RT5MM 2.1.4 to 2.1.5 with matched screen-on time, brightness, ambient conditions, battery temperature and app workload. Do not attribute total phone battery differences to the sensor service without controls.
5. Compare system slider position and visible luminance for raw **17, 19, 49, 52** on K90; confirm both dim-room comfort and sunlight readability.
6. Check long foreground operation, screen on/off, OS process reclaim, and reboot behavior on HyperOS 4.

## Tests and build

GitHub Actions executes existing and expanded `testDebugUnitTest` before `assembleDebug`. The successful debug APK is attached to the versioned **2.1.5 Adaptive Core debug** prerelease and the existing `screen-protection-latest` channel. Debug APKs are Android Gradle Plugin debug-signed; no release certificate is needed. CI tests exercise pure logic, not actual Redmi K90 sensor HAL, OLED luminance, boot behavior, or battery consumption.
