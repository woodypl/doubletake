# doubletake for Android

Mirrors an Android phone's screen and playback audio to an AirPlay receiver
using doubletake's protocol code (`../mobile`, bound with gomobile).

- Video: MediaProjection → virtual display → hardware H.264 encoder
  (Baseline, receiver canvas size, 30 fps).
- Audio: AudioPlaybackCapture → 44.1 kHz stereo PCM → ALAC in Go.
- Pairing: on-screen PIN or password prompt; credentials are kept in app
  storage and reused (pair-verify) on later sessions.

Requires Android 11 or later.

## Building

GitHub Actions (`.github/workflows/android.yml`) builds a debug APK on every
push to the `android` branch and attaches it to the run as an artifact.

Locally: install Go, the Android SDK/NDK and gomobile, then

```
gomobile bind -target android/arm64 -androidapi 30 -javapkg dtcore \
  -o android/app/libs/doubletake.aar ./mobile
cd android && gradle assembleDebug
```

## Limitations

- Apps that block screen capture (most DRM video) show black; apps that opt
  out of audio capture are silent.
- Audio also keeps playing on the phone itself. Whether lowering the phone volume
  also lowers the captured audio is untested.
- Only H.264 video and ALAC audio are implemented.
