# Android Virtual Video

Android video-loop player prototype.

## V1 features

- Select local video from gallery/file picker
- Preview video
- Continuous loop
- Play / pause
- Mute / unmute
- FIT / CROP preview mode
- Remembers the last selected video
- Portrait UI for 9:16 workflows
- GitHub Actions debug APK build

## Build locally

Open this repository in Android Studio and let Gradle sync.

Requirements:

- JDK 17
- Android SDK 35

Then run the `app` configuration.

## Build on GitHub

Every push to `main` runs:

```
gradle :app:assembleDebug
```

When the workflow finishes, download the `virtual-video-debug` artifact from the Actions run.

## Current scope

This first version is a standalone local video player/loop engine. It does not replace another app's camera input.
