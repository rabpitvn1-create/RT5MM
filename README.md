# Redmi Screen Protection

A personal one-button Android screen protection app for Redmi / HyperOS.

The app is not meant to be a general brightness controller. Its job is to keep the screen inside conservative brightness levels, run quietly in the foreground when enabled, respect manual brightness changes, save battery where possible, and restore protection after reboot when possible.

## Latest APK update

- Version: 1.0.32
- Release channel: debug APK
- Release tag: `screen-protection-latest`
- APK filename: `Screen-Protection-debug.apk`
- Release note: adds a confirmed deep-night raw floor so true 0 lux can settle near raw 4 instead of raw 7.

## Product direction

Main screen philosophy:

- One primary button: turn protection on or off.
- Short status text: ready, protecting, holding user brightness, blocked, or limited.
- Permission and HyperOS setup are support flows, not the main interface.

Internal behavior:

- Uses the ambient light sensor while the screen is on.
- Suspends sensor work when the screen is off while keeping protection enabled.
- Turns the system auto-brightness mode off while protection is enabled, then restores the previous system mode when protection is stopped.
- Keeps brightness inside conservative protection buckets.
- Uses a foreground service while protection is enabled.
- Restores protection after boot or app update if it was enabled.
- Treats manual brightness changes as a user hold instead of immediately fighting the user.

## System brightness handoff

Screen Protection now takes exclusive control more cleanly:

- When protection starts, the app captures the previous system brightness mode.
- It immediately switches Android/HyperOS brightness mode to `MANUAL` so the system auto-brightness controller does not run in parallel.
- While protection is running, screen wake and service refresh paths force `MANUAL` again in case another feature re-enabled auto brightness.
- When protection stops, the app restores the captured previous mode. If the system was using auto brightness before protection started, it is restored to auto; if it was already manual, it stays manual.

## Brightness Brain v3 active path

This version connects the Brightness Brain foundation into the active manager path:

- `ProtectionCurveEngine` supplies the active lux-to-raw target.
- `AutoBrightnessManager` no longer jumps directly to bucket brightness during normal protection decisions.
- The manager writes a protected raw target and lets `ProtectionTransitionEngine` move toward it smoothly.
- Transition is cancelled on screen-off sleep, service stop, force recovery, noisy sensor decisions, spike rejection, and confirmed user brightness hold.
- The raw target is saved as the last app-driven raw value so the brightness observer can distinguish app writes from real user changes.
- User hold is shortened for personal-mode behavior: normal hold is about 15 minutes; night hold is about 8 minutes.

## Deep Night Floor

Real-world tuning showed that raw 7 can still feel bright at 0 lux. The active raw curve now supports a lower confirmed deep-night floor:

- 0 to 0.5 lx -> raw 4
- 0.5 to 1.5 lx -> raw 5
- 1.5 to 3 lx -> raw 6
- 3 to 6 lx -> raw 7

This does not mean every sudden 0 lux reading immediately forces raw 4. Deep-night targets go through the decision gate:

- They need a rolling-window confirmation.
- They require stronger sample agreement than normal changes.
- They use a slower confirmation time than ordinary dimming.
- Force-refresh paths do not bypass the deep-night confirmation guard.

This is meant to handle true dark-room use without letting a hand-covered sensor or short lux drop instantly make the screen too dark.

## Brightness Decision Gate

The app no longer treats every lux change as a reason to change brightness. The core question is now:

`Is there enough evidence to change protected raw brightness?`

`BrightnessDecisionEngine` keeps a short rolling window of lux-derived raw targets and decides between:

- `NOOP`: current raw already matches the stable target.
- `WAIT`: more evidence is needed.
- `IGNORE_SPIKE`: the latest lux jump looks like a short spike.
- `SENSOR_NOISY`: samples are too inconsistent to trust.
- `APPLY`: the target raw is confirmed and can be transitioned.

Important behavior:

- Decisions are based on target raw, not lux alone.
- A one-sample spike is not allowed to write brightness.
- Small raw changes need stronger confirmation than large upward changes.
- Downward changes are confirmed more slowly than upward changes.
- Deep-night downward changes are confirmed slowest.
- Same-target maintenance is throttled so the app does not keep refreshing its own last-write timestamp and accidentally hide real user adjustments.

This replaces the earlier hard same-room sensor pause. The app keeps observing while the screen is on, but it becomes harder for noisy lux data to trigger brightness writes.

## Battery-aware protection

The app uses a battery-aware protection layer:

- `ACTIVE_SCREEN_ON`: sensor is active and protection evaluates brightness.
- `SCREEN_OFF_SLEEP`: screen is off, the light sensor is unregistered, interval evaluation is suspended, and the notification avoids live raw-brightness reads.
- `RECOVERY_WAKE`: screen just woke, service restarted, or protection was refreshed; the app evaluates quickly once and returns to active mode.
- `USER_HOLD_LOW_POWER`: the user has manually changed brightness, so protection backs off and evaluates less aggressively.

Battery strategy:

- Screen off -> unregister light sensor and stop protection interval ticks.
- Screen on -> register light sensor and recovery evaluate.
- Sensor samples are kept as observations; brightness writes are gated by the decision engine.
- Strong upward evidence can apply faster than downward evidence.
- Last lux persistence uses RAM cache first and avoids reading SharedPreferences on the sensor hot path.
- Battery counters are RAM-first and flushed on service stop instead of writing SharedPreferences on every sensor sample.
- Manual brightness changes are detected by a `SCREEN_BRIGHTNESS` ContentObserver instead of relying on the sensor loop.
- App-write grace is started before app-driven brightness writes so the observer does not mistake the app's own write for a user override.
- Service health understands `SCREEN_OFF_SLEEP`, so sleep mode is not falsely reported as stale/limited.
- Normal sensor-sample logs are suppressed unless a decision is important; diagnostic mode shows battery counters.

## Current protection buckets

These are the raw values currently used by the app. The curve includes guarded deep-night buckets below the normal 12% floor:

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

The percentages are app-level protection buckets, not a promise that Android or HyperOS will display the same visual percentage on every device.

## Active raw curve

The active protection curve is intentionally dense in the dark-room range, then gradually wider in bright-room and outdoor ranges:

- up to 0.5 lx -> raw 4
- up to 1.5 lx -> raw 5
- up to 3 lx -> raw 6
- up to 6 lx -> raw 7
- up to 10 lx -> raw 11
- up to 16 lx -> raw 13
- up to 25 lx -> raw 14
- up to 40 lx -> raw 16
- up to 65 lx -> raw 17
- up to 100 lx -> raw 19
- up to 150 lx -> raw 21
- up to 230 lx -> raw 23
- up to 350 lx -> raw 25
- up to 520 lx -> raw 28
- up to 800 lx -> raw 31
- up to 1200 lx -> raw 34
- up to 1800 lx -> raw 37
- up to 2600 lx -> raw 40
- up to 3800 lx -> raw 43
- up to 5500 lx -> raw 46
- above 5500 lx -> raw 49

Movement rules:

- Dimming into raw 4, 5, or 6 requires stronger confirmation.
- Leaving deep-night brightness is faster than entering it.
- Strong upward evidence can move sooner than downward evidence.
- User brightness changes still override protection through user hold.

## Quick Settings Tile

The tile is aligned with the one-button product direction:

- Tap to turn Screen Protection on.
- Tap again to turn it off.
- Manual brightness cycling is no longer the primary tile behavior.

## Required setup

For reliable protection on HyperOS, grant these manually when prompted:

- Modify system settings
- Notification permission on Android 13+
- Unrestricted battery / no battery optimization
- HyperOS Autostart / No restrictions / Lock in Recents when available

## Inspiration for future algorithm work

Future protection-policy work should borrow ideas from:

- `wluma`: learn from manual user brightness changes after a cooldown instead of reacting to every small adjustment.
- `Clight`: use curves, smooth transitions, sensor-rejection logic, and pause/resume behavior instead of scattered if/else rules.
- `Auto-Shine`: event-driven sampling, interpolated curves, and minimal intervention.

The app should still keep the core promise: simple outside, intelligent inside.
