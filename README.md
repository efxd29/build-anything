# Scrcpy — Android-to-Android mirroring

Scrcpy is a personal Android-to-Android screen mirroring and control app.
One phone (the *controller*) mirrors and drives another phone (the
*target*) — over local Wi-Fi via **wireless debugging**, or over an OTG
cable via **USB debugging**. Everything runs directly between the two
devices on your own network or cable: no internet, no account, no cloud,
nothing in between.

The app is an original client built on the ideas of the
[scrcpy project](https://github.com/Genymobile/scrcpy): the official
scrcpy-server (v3.3.4) runs on the target, while everything on the
controller side — the ADB protocol stack, the wireless pairing, the
streaming pipeline and the UI — is written from scratch for this project.

## Features

- **Wireless debugging connect** — pair once with the 6-digit pairing code
  (SPAKE2 + AES-128-GCM, exactly the protocol Android uses), then connect
  to the target's wireless debugging port over TLS. Endpoints on the local
  network are discovered automatically via mDNS
  (`_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp`).
- **USB debugging connect** — plug the target in with an OTG cable, tap
  Allow on it, and mirror. The same RSA identity is used, so a key trusted
  over USB is also trusted wirelessly.
- **Legacy TCP/IP mode** — plain ADB over TCP (port 5555) with classic
  token authentication and STLS upgrade, for devices set up with
  `adb tcpip`.
- **Free bitrate control** — video bitrate from 1 to 200 Mbps, chosen by
  slider or preset chips.
- **Up to 144 fps** — frame-rate cap adjustable from 30 to 144 fps; the
  decoder runs with the low-latency hint, real-time priority and an
  operating rate matched to the cap, and the display surface is told to
  prefer the session frame rate. A live stats chip shows the real fps and
  throughput while you mirror.
- **Latency-first pipeline** — packets are rendered as soon as they are
  decoded (never queued), stale frames are dropped instead of buffered,
  touch MOVE events are coalesced to the freshest position, and all
  sockets run with TCP_NODELAY.
- **Codecs** — H.264, H.265 and AV1 video; Opus, AAC, FLAC or raw audio.
- **Resolution cap** — native, or capped at 1920 / 1280 / 854 px.
- **On-screen toolbar** — Back, Home, Recents, notifications, volume,
  rotate and power, in a floating pill while mirroring.
- Session options: stay awake on target, turn target screen off, show
  touches on target.

## How it works

1. **Identity.** On first launch the app creates an RSA-2048 key pair —
   its ADB identity — stored in the app's private preferences. The same
   key signs classic ADB auth tokens, is what the target stores when you
   tap *Allow*, and backs the self-signed certificate used for
   ADB-over-TLS.
2. **Pairing (wireless, once).** The pairing client opens TLS 1.3 to the
   target's pairing port, exports 64 bytes of TLS keying material
   (`adb-label`), and runs SPAKE2 with the pairing code. The session key
   (HKDF-SHA256, `adb pairing_auth aes-128-gcm key`) encrypts the peer
   info exchange that hands the target our public key.
3. **Connection.** Wireless connections are TLS from the first byte;
   legacy connections start plain and upgrade via A_STLS when asked; USB
   uses the ADB bulk interface. In all cases the app speaks the ADB wire
   protocol itself — no `adb` binary involved.
4. **Session.** The official scrcpy-server is pushed to
   `/data/local/tmp/scrcpy-server.jar` (only when its MD5 differs) and
   started via `app_process` with your bitrate / fps / codec choices.
   Video, audio and control travel over three tunnelled ADB streams.
5. **Rendering & control.** Video is decoded with MediaCodec straight to
   the surface; audio is decoded to a low-latency AudioTrack; touches are
   scaled to the target's resolution and sent as scrcpy control messages.

## Building

Requirements: JDK 17 and the Android SDK (platform 34, build-tools
34.0.0). Gradle 8.11.1 is provisioned by the wrapper (or by
`gradle/actions/setup-gradle` in CI).

```sh
gradle :app:assembleDebug     # or assembleRelease
```

The pinned official `scrcpy-server` binary is **not** stored in this
repository; the `fetchScrcpyServer` Gradle task downloads it from the
Genymobile/scrcpy v3.3.4 release into `app/src/main/assets/` on first
build.

GitHub Actions builds both APKs on every push to `main` and publishes
them to a GitHub release (`build-<run number>`).

## Permissions

Only `INTERNET` and `ACCESS_NETWORK_STATE` — both normal permissions.
The app talks exclusively to the target device you choose.

## Notes & limits

- The real frame-rate ceiling is set by the target: its display refresh
  rate, its encoder, and your Wi-Fi. 144 fps needs a target that can
  capture and encode that fast.
- Wireless debugging requires Android 11+ on the target; USB debugging
  and legacy TCP/IP work on older versions (app minimum is Android 8).
- The release APK is signed with the debug key for personal use.
