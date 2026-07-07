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

These are the raw values currently used by the app:

- 20% = raw 11
- 30% = raw 17
- 40% = raw 26
- 50% = raw 38
- 60% = raw 49

The percentages are app-level protection buckets, not a promise that Android or HyperOS will display the same visual percentage on every device.

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
