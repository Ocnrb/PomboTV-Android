# PomboTV

Native Android app for live one-to-many video streaming over [Streamr](https://streamr.network), a peer-to-peer real-time data network. One device broadcasts camera + mic, any number of devices watch — no media server.

## Stack

- **Kotlin**, plain Views (no Compose), `SurfaceView` for preview and decoded video.
- **Camera2** capture: preview surface + a second surface feeding the encoder directly.
- **MediaCodec** for H.264 (surface input) and Opus, hardware-accelerated where available.
- **AudioRecord / AudioTrack** for capture and playback; the AudioTrack playback head drives the session clock.
- **Streamr JS SDK** for transport — no native Android SDK exists, so the client runs in a headless `WebView` (`assets/streamr_bridge.html`), bridged to Kotlin as binary-over-base64. `StreamrBridge.kt` is the only file aware of this; the rest of the app just sees byte arrays.
- **Foreground Service** (`LiveService.kt`, camera/microphone/mediaPlayback types) keeps a broadcast or live view running in the background with a detachable `Surface`.

## Wire protocol

Custom binary format (20-byte header + fragmentation, `Wire.kt`), shared byte-for-byte with the project's web client — an Android broadcaster and a browser viewer (or vice versa) are wire-compatible on the same Streamr stream. `AvcUtils.kt` converts H.264 avcC/AVCC ↔ Annex-B for that interop.

## Mesh vs. proxy delivery

Streamr's default gossip mesh has every node relay for others, so a broadcaster's real uplink is a multiple of its encode bitrate. This app also supports **proxy publish/subscribe**: given a funded Sponsorship contract on the stream, `findProxyNodes()` discovers staked Operator nodes and `setProxies()` connects to them directly. In proxy mode the phone is a pure leaf (no relaying either direction), so uplink drops to ~1–2× bitrate instead of ~4×. Discovery runs per stream partition with automatic mesh fallback and re-promotion once operators reappear. `tools/create-sponsorship.js` deploys a funded Sponsorship for testing this path.

## Adaptive delivery

- **Jitter buffer**: adaptive target that grows under jitter and decays when calm, live re-anchor on drift, decoder-wedge detection with rebuild.
- **Render pacing**: decoded frames are held and released at their due time — releasing early floods the `SurfaceView` buffer queue and wedges the decoder.
- **Congestion control**: app-level ABR — the bridge ACKs every packet, Kotlin tracks in-flight bytes, and a controller cuts encoder bitrate under backlog, drops to keyframes-only under severe congestion, ramps back up when calm.
- **Network resilience**: `ConnectivityManager` callbacks trigger a debounced reconnect/resubscribe on network change; a delivery watchdog detects a silently dead path and forces rediscovery.

## Other features

- Pinch-to-zoom across the full camera range including ultrawide, `CONTROL_ZOOM_RATIO` with crop-region fallback, torch control, camera enumeration by focal length.
- Mic mute sends zeroed PCM instead of stopping the stream, keeping the receiver's audio clock alive.
- Always-on lightweight audio subscription detects an active broadcast without joining the full overlay.
- Configurable proxy count per role (mesh/1/2/3), persisted across sessions.

## Build

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" assembleDebug
```

or open the project in Android Studio. APK: `app/build/outputs/apk/debug/app-debug.apk`. Requires Android 10+ (API 29) and camera/mic/internet permissions.

## tools/

- `create-sponsorship.js` — deploys and funds a Streamr Sponsorship contract on Polygon for proxy-mode testing.
- `relay.js` — bridges the Android emulator into the mesh over a local WebSocket; not needed on real devices.
