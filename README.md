# Android Virtual Video

Experimental Android virtual-video research project.

## Vir Vid 3

Vir Vid 3 uses a different application ID:

`id.armagic.virvid3`

This means it can be installed alongside earlier builds without uninstalling them.

### V1

- Select local video
- Preview
- Loop
- Play / pause
- Mute
- FIT / CROP

### V2

- Shizuku integration
- ADB / Developer Options detection
- Surface readiness test

### V3

- Camera2 camera ID enumeration
- Front / back / external lens detection
- Hardware level detection
- Sensor orientation
- Camera capabilities
- SurfaceTexture output sizes
- Concurrent camera sets
- Camera extension modes
- Camera service probe through Shizuku shell
- Separate APK identity: **Vir Vid 3**

V3 is still a capability probe. It does not yet replace the camera input of another app.

## Build

GitHub Actions builds the debug APK automatically.

Artifact name:

`vir-vid-3-debug`
