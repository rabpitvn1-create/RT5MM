# Redmi Screen Protection

A personal one-button Android screen protection app for Redmi / HyperOS.

The app is not meant to be a general brightness controller. Its job is to keep the screen inside conservative brightness levels, run quietly in the foreground when enabled, respect manual brightness changes, and restore protection after reboot when possible.

## Product direction

Main screen philosophy:

- One primary button: turn protection on or off.
- Short status text: ready, protecting, holding user brightness, blocked, or limited.
- Permission and HyperOS setup are support flows, not the main interface.

Internal behavior:

- Uses the ambient light sensor.
- Keeps brightness inside conservative protection buckets.
- Uses a foreground service while protection is enabled.
- Restores protection after boot or app update if it was enabled.
- Treats manual brightness changes as a user hold instead of immediately fighting the user.

## Current protection buckets

These are the raw values currently used by the app. The curve now uses 17 protection buckets for smoother movement:

- 20% = raw 11
- 23% = raw 13
- 25% = raw 14
- 28% = raw 16
- 30% = raw 17
- 33% = raw 20
- 35% = raw 22
- 38% = raw 24
- 40% = raw 26
- 43% = raw 29
- 45% = raw 32
- 48% = raw 35
- 50% = raw 38
- 53% = raw 41
- 55% = raw 44
- 58% = raw 47
- 60% = raw 49

The percentages are app-level protection buckets, not a promise that Android or HyperOS will display the same visual percentage on every device.

## Lux curve

The protection policy uses 17 lux bands so brightness changes one small bucket at a time instead of jumping across large ranges:

- up to 8 lx -> 20%
- up to 13 lx -> 23%
- up to 20 lx -> 25%
- up to 35 lx -> 28%
- up to 60 lx -> 30%
- up to 85 lx -> 33%
- up to 120 lx -> 35%
- up to 175 lx -> 38%
- up to 250 lx -> 40%
- up to 355 lx -> 43%
- up to 500 lx -> 45%
- up to 710 lx -> 48%
- up to 1000 lx -> 50%
- up to 1580 lx -> 53%
- up to 2500 lx -> 55%
- up to 5000 lx -> 58%
- above 5000 lx -> 60%

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
