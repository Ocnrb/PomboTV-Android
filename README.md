# Live POC — Android nativo

Porta nativa Android do `live-poc.html` (Streamr + WebCodecs). Um transmissor → muitos espectadores, nos mesmos streams Streamr do POC web — **interoperável nos dois sentidos** (transmitir no browser e ver no Android, ou o contrário).

## Arquitetura

| Camada | Web (live-poc.html) | Android |
|---|---|---|
| Transporte | `@streamr/sdk` (JS) | O mesmo SDK JS, num **WebView invisível** que serve só de ponte binária (base64) — não existe SDK Streamr nativo |
| Captura | getUserMedia | Camera2 (preview + surface do encoder) + AudioRecord |
| Encode | WebCodecs H.264 / Opus | MediaCodec H.264 (surface input) / MediaCodec Opus |
| Wire format | header 20B + fragmentação + containers | `Wire.kt` — porta byte a byte do mesmo formato |
| Decode | WebCodecs → canvas / AudioContext | MediaCodec → SurfaceView / AudioTrack |
| Relógio | áudio-mestre + jitter buffer | igual (playback head do AudioTrack como mestre) |

Interop H.264: o Android emite Annex-B com SPS/PPS em cada keyframe e config **sem** `description` (o WebCodecs assume Annex-B); ao receber do browser converte avcC/AVCC → Annex-B (`AvcUtils.kt`).

## Compilar

Abrir a pasta `android-poc` no Android Studio, ou por linha de comandos:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" -p android-poc assembleDebug
```

APK em `app/build/outputs/apk/debug/app-debug.apk`. Instalar com `adb install -r app-debug.apk`.

Requisitos: Android 10+ (API 29, pelo encoder Opus via MediaCodec), permissões de câmara/micro, internet.

## Diferenças vs. o POC web (simplificações deliberadas)

- Sem partilha de ecrã (exigiria MediaProjection) — só câmara.
- Sem sliders de A/V trim / decode por software (o buffer é adaptativo como no web — 400 ms base, cresce até 1.5 s sob jitter; o rebuild do decoder cobre o caso de wedge, com detetor de wedge silencioso igual ao do web).
- Rotação livre; o STREAM mantém-se sempre landscape (orientação do sensor) — transmitir em portrait envia vídeo deitado, como aconteceria no web com uma webcam.
- Escolha de câmara por frente/trás em vez de lista de dispositivos.

## Testar o VIEWER no emulador (importante)

O WebRTC browser↔browser através do NAT do emulador é instável — o nó do emulador pode não conseguir ligar-se diretamente a um broadcaster em browser (áudio chega às vezes, vídeo grande não). Solução para testes: correr um nó relay no PC, que entra na malha e faz ponte:

```
cd tools && npm i @streamr/sdk@103.3.1 && node relay.js
```

Em telemóveis reais com rede normal isto NÃO é necessário. A ponte tem ainda um scaffold de proxy dev (`USE_DEV_PROXY` em `streamr_bridge.html`) que faz `setProxies` para o relay via `ws://10.0.2.2:32200` — desligado por omissão; é também o protótipo do modo proxy de produção (findProxyNodes + Sponsorship).

## Notas

- A ponte (`assets/streamr_bridge.html`) carrega o SDK do unpkg — o arranque precisa de rede.
- Os streams/partição são os mesmos do POC (`0x75fc…/video`, `/audio`, partição 0); o transmissor também subscreve os próprios overlays (mesma razão do POC: publisher puro tem má entrega).
- Payloads passam a ponte em base64 (~25 msgs/s) — folga larga para 2 Mbps.
