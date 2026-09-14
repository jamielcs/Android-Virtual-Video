# Android Virtual Video

Experimental Android virtual-video research project.

## Vir Vid 4

Vir Vid 4 uses its own application ID:

`id.armagic.virvid4`

It can be installed alongside earlier Vir Vid builds without uninstalling them.

### V4 focus

- Camera2 device enumeration
- Camera front/back/external detection
- Hardware level and stream capability inspection
- Camera/media service listing
- Camera process discovery
- HAL/HIDL/AIDL camera provider hints via `lshal`
- Camera-related system/vendor properties
- Focused `dumpsys media.camera` filtering
- Search for provider/device/vendor/external/virtual clues
- Shizuku shell access for diagnostics
- No long raw dump and no intentional broken-pipe output

V4 is still a diagnostic build. It does not yet replace another app's camera input.

## Build

GitHub Actions builds the debug APK automatically.

Artifact:

`vir-vid-4-debug`
