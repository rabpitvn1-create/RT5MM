# Redmi Screen Brightness

Android Quick Settings Tile app for Redmi Turbo 5 Max / HyperOS 3.

Locked brightness raw values:

- 20% = 11
- 30% = 17
- 40% = 26
- 50% = 38
- 60% = 49

Behavior:

- Launcher app opens a real Android Activity for permission/status checking.
- Quick Settings Tile cycles 20 -> 30 -> 40 -> 50 -> 60 -> 20.
- Tile icon changes to sun with 2 / 3 / 4 / 5 / 6 rays.
- Uses Android Gradle Plugin, Java, D8 and normal Android packaging.

Build output artifact name in GitHub Actions: `Redmi Screen Brightness.apk`.
