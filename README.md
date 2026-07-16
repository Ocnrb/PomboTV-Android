# PomboTV — Native Android

Native Android port of `live-poc.html` (Streamr + WebCodecs). One broadcaster → many viewers, on the same Streamr streams as the web POC — **interoperable both ways** (broadcast in the browser and watch on Android, or the other way around).

## Architecture

| Layer | Web (live-poc.html) | Android |
|---|---|---|
| Transport | `@streamr/sdk` (JS) | The same JS SDK, in an **invisible WebView** used purely as a binary bridge (base64) — no native Streamr SDK exists |
| Capture | getUserMedia | Camera2 (preview + encoder surface) + AudioRecord |
| Encode | WebCodecs H.264 / Opus | MediaCodec H.264 (surface input) / MediaCodec Opus |
| Wire format | 20B header + fragmentation + containers | `Wire.kt` — byte-for-byte port of the same format |
| Decode | WebCodecs → canvas / AudioContext | MediaCodec → SurfaceView / AudioTrack |
| Clock | audio-master + jitter buffer | same (AudioTrack playback head as master) |

H.264 interop: Android emits Annex-B with SPS/PPS on every keyframe and config **without** `description` (WebCodecs assumes Annex-B); when receiving from the browser it converts avcC/AVCC → Annex-B (`AvcUtils.kt`).

## Build

Open the `android-poc` folder in Android Studio, or from the command line:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" -p android-poc assembleDebug
```

APK at `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r app-debug.apk`.

Requirements: Android 10+ (API 29, for the Opus encoder via MediaCodec), camera/mic permissions, internet.

## Differences vs. the web POC (deliberate simplifications)

- No screen sharing (would require MediaProjection) — camera only.
- No A/V trim sliders / software decode (the buffer is adaptive like on web — 400ms base, grows up to 1.5s under jitter; decoder rebuild covers the wedge case, with the same silent-wedge detector as the web version).
- Free rotation; the STREAM always stays landscape (sensor orientation) — broadcasting in portrait sends sideways video, same as would happen on web with a webcam.
- Camera picked by front/back instead of a device list.

## Testing the VIEWER in the emulator (important)

Browser↔browser WebRTC through the emulator's NAT is unstable — the emulator's node may fail to connect directly to a browser broadcaster (audio sometimes arrives, large video never does). Workaround for testing: run a relay node on the PC that joins the mesh and bridges:

```
cd tools && npm i @streamr/sdk@103.3.1 && node relay.js
```

On real phones on a normal network this is NOT necessary. The bridge also has a dev proxy scaffold (`USE_DEV_PROXY` in `streamr_bridge.html`) that does `setProxies` against the relay via `ws://10.0.2.2:32200` — off by default; it's also the prototype for the production proxy mode (findProxyNodes + Sponsorship).

## Notes

- The bridge (`assets/streamr_bridge.html`) loads the SDK from unpkg — startup needs network access.
- The streams/partition are the same as the POC (`0x75fc…/video`, `/audio`, partition 0); the broadcaster also subscribes to its own overlays (same reason as the POC: a pure publisher gets poor delivery).
- Payloads cross the bridge as base64 (~25 msgs/s) — plenty of headroom for 2 Mbps.
