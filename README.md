# PomboTV

Native Android app for live one-to-many video streaming over [Streamr](https://streamr.network), a peer-to-peer real-time data network. One device broadcasts (camera + mic), any number of devices watch — no media server, no central point of delivery.

## Stack

- **Kotlin**, no Compose — plain Views, `SurfaceView` for camera preview/decode output.
- **Camera2** for capture (preview surface + a second surface feeding the encoder directly, no intermediate buffer copy).
- **MediaCodec** for both directions: H.264 video (surface input) and Opus audio, hardware-accelerated where the device supports it.
- **AudioRecord / AudioTrack** for raw PCM capture and playback; the AudioTrack playback head is the session clock (audio-master).
- **Streamr JS SDK** for network transport. There is no native Streamr SDK for Android, so the client runs inside a headless, invisible `WebView` (`assets/streamr_bridge.html` + `assets/streamr-sdk.min.js`), and Kotlin talks to it over the JS bridge as binary-over-base64. `StreamrBridge.kt` is the only file that knows this — everything else sees plain byte arrays.
- **Foreground Service** (`LiveService.kt`) with `camera`/`microphone`/`mediaPlayback` types, so a broadcast or a live view survives the app going to background (partial wake lock, detachable `Surface` — video decode/capture pauses without dropping audio or killing the session).

## Wire protocol

Video and audio are packetized with a custom binary format (20-byte header + fragmentation, `Wire.kt`) shared byte-for-byte with the project's web client, so an Android broadcaster and a browser viewer (or vice versa) are wire-compatible on the same Streamr stream. The only format translation needed is H.264 bitstream shape: Android emits Annex-B (SPS/PPS inline on every keyframe, no out-of-band `description`); frames arriving from a browser encoder (avcC/AVCC) are converted to Annex-B on the fly (`AvcUtils.kt`).

## Delivery: mesh vs. proxy

By default Streamr delivers over a gossip mesh — every node relays for others, which means a P2P broadcaster's real uplink is a multiple of its encode bitrate (fan-out to neighbors), which is expensive on a mobile uplink. This app also supports **proxy publish/subscribe**: given an active (funded) Sponsorship contract on the stream, `client.findProxyNodes()` discovers staked Operator nodes and `setProxies()` connects directly to them in `ProxyDirection.PUBLISH` or `SUBSCRIBE`. In proxy mode the phone is a pure leaf — it neither relays for others nor gets relayed-to by peers — so a broadcaster's uplink drops to roughly 1–2× its bitrate instead of ~4×, and a viewer's downlink is exactly its subscribed rate. Proxy discovery runs per stream partition (video/audio share one partitioned stream), with automatic mesh fallback if no proxies are found or a proxy connection dies silently, and periodic re-promotion back to proxy mode once operators reappear. `tools/create-sponsorship.js` deploys a funded Sponsorship contract for testing this path.

## Adaptive delivery

- **Jitter buffer / clock**: audio-master clock derived from the AudioTrack playback head; adaptive buffer target (grows under jitter, decays when calm); live re-anchor when the edge drifts too far from target; decoder-wedge detector that rebuilds the decoder instead of leaving playback stuck.
- **Render pacing**: decoded video frames are held and released to the `Surface` at their due time rather than as soon as decoded — releasing frames far ahead of schedule floods the `SurfaceView` buffer queue and can wedge the decoder.
- **Congestion control**: no access to the connection's own congestion signals (WebRTC/QUIC internals aren't exposed at this layer), so bitrate control is done at the app level — the JS bridge ACKs every published packet, Kotlin tracks in-flight bytes, and a controller loop cuts the encoder bitrate (`PARAMETER_KEY_VIDEO_BITRATE`) under sustained backlog, drops to keyframes-only under severe congestion, and ramps back up once the channel is calm.
- **Network resilience**: `ConnectivityManager` network-change callbacks trigger a bridge reconnect (debounced) that rejoins/resubscribes without restarting the app; a delivery watchdog on the JS side detects a silently dead subscription/publish path and forces rediscovery.

## Other device-facing features

- Pinch-to-zoom across the full camera range including ultrawide (<1×) lenses, with `CONTROL_ZOOM_RATIO` where available and a crop-region fallback otherwise; torch control; camera enumeration/labeling by focal-length ratio against the main sensor.
- Mic mute sends zeroed PCM rather than stopping the audio stream, so the receiving side's audio-driven clock keeps running.
- A lightweight, always-on audio-only subscription (independent of any active session) detects whether a broadcast is currently live, without joining the full stream overlay.
- Configurable proxy count per role (mesh/1/2/3), persisted and pushed to the bridge on every (re)connect.

## Build

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" assembleDebug
```

or open the project in Android Studio. APK output: `app/build/outputs/apk/debug/app-debug.apk`. Requires Android 10+ (API 29, for the Opus MediaCodec encoder) and camera/mic/internet permissions.

## tools/

- `create-sponsorship.js` — deploys and funds a Streamr Sponsorship contract on Polygon for a given stream, to enable proxy-mode testing (private key read from a local, gitignored `.env`, never hardcoded).
- `relay.js` — a PC-side Streamr node used only to bridge the Android emulator (whose WebRTC-to-browser path is unreliable) into the mesh over a deterministic local WebSocket; not needed on real devices.
