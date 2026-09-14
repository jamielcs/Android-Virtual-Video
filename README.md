# Android Virtual Video

Android non-root virtual-video experiment.

## V1 — working player engine

- Select local video from gallery/file picker
- Preview video
- Continuous loop
- Play / pause
- Mute / unmute
- FIT / CROP preview mode
- Remembers the last selected video
- Portrait UI for 9:16 workflows

## V2 — Virtual Camera Lab

- Device / Android API detection
- Developer Options detection
- ADB state detection
- Shizuku binder detection
- Shizuku runtime permission request
- Shizuku UID display
- Video Surface readiness test
- Explicit bridge status

Shizuku provides ADB/shell-level privileges when the user starts Shizuku and grants permission. It does not by itself create a system-wide virtual camera.

## Shizuku setup

1. Install Shizuku.
2. Enable Developer Options.
3. Enable Wireless Debugging or start Shizuku using ADB.
4. Start Shizuku.
5. Open Virtual Video.
6. Tap Refresh Capability.
7. Tap Minta Izin Shizuku.
8. Select a video.
9. Tap Test Video Surface.

## Build

Requirements:

- JDK 17
- Android SDK 35

GitHub Actions builds the debug APK on every push to main.
