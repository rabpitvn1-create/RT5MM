# Redmi Screen Protection

A personal one-button Android screen protection app for Redmi / HyperOS.

The app is not meant to be a general brightness controller. Its job is to keep the screen inside conservative brightness levels, run quietly in the foreground when enabled, respect manual brightness changes, save battery where possible, and restore protection after reboot when possible.

## Latest APK update

- Version: 1.0.25
- Release channel: debug APK
- Release tag: `screen-protection-latest`
- APK filename: `Screen-Protection-debug.apk`

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

## Battery-aware protection

The app now uses a battery-aware protection layer:

- `ACTIVE_SCREEN_ON`: sensor is active and protection evaluates brightness.
- `SCREEN_OFF_SLEEP`: screen is off, the light sensor is unregistered, interval evaluation is suspended, and the notification avoids live raw-brightness reads.
- `RECOVERY_WAKE`: screen just woke, service restarted, or protection was refreshed; the app evaluates quickly once and returns to active mode.
- `USER_HOLD_LOW_POWER`: the user has manually changed brightness, so protection backs off and evaluates less aggressively.

Battery v2 strategy:

- Screen off -> unregister light sensor and stop protection interval ticks.
- Screen on -> register light sensor and recovery evaluate.
- Same lux band -> throttle evaluation instead of running the full decision pipeline every sensor sample.
- Strong lux rise -> evaluate immediately so brightness can recover quickly.
- Last lux persistence uses RAM cache first and avoids reading SharedPreferences on the sensor hot path.
- Battery counters are RAM-first and flushed on service stop instead of writing SharedPreferences on every sensor sample.
- Manual brightness changes are detected by a `SCREEN_BRIGHTNESS` ContentObserver instead of relying on the sensor loop.
- App-write grace is started before app-driven brightness writes so the observer does not mistake the app's own write for a user override.
- Service health understands `SCREEN_OFF_SLEEP`, so sleep mode is not falsely reported as stale/limited.
- Normal sensor-sample logs are suppressed unless a decision is important; diagnostic mode shows battery counters.

## Current protection buckets

These are the raw values currently used by the app. The curve now uses 20 protection buckets, including a guarded night-protection range below 20% and a smoother mid-high raw progression:

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

## Lux curve

The protection policy uses 20 lux bands. It is intentionally dense at night and indoors, then gradually wider in bright-room and outdoor ranges:

- up to 1 lx -> 12%
- up to 3 lx -> 15%
- up to 6 lx -> 18%
- up to 10 lx -> 20%
- up to 16 lx -> 23%
- up to 25 lx -> 25%
- up to 40 lx -> 28%
- up to 65 lx -> 30%
- up to 100 lx -> 33%
- up to 150 lx -> 35%
- up to 230 lx -> 38%
- up to 350 lx -> 40%
- up to 520 lx -> 43%
- up to 800 lx -> 45%
- up to 1200 lx -> 48%
- up to 1800 lx -> 50%
- up to 2600 lx -> 53%
- up to 3800 lx -> 55%
- up to 5500 lx -> 58%
- above 5500 lx -> 60%

Movement rules:

- Dimming into 12%, 15%, or 18% requires longer stability.
- Leaving night-protection brightness is faster than entering it.
- Strong lux increases can move more than one bucket.
- Write budget is dynamic: slower for sub-20 dimming, faster for upward movement.

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

The app should still keep the core promise: simple outside, intelligent inside.
