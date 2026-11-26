# agent-desktop Android port — CI tree

Private build repository for the Android port of vercel-labs/agent-desktop
v1.3.1. It carries exactly what the GitHub Actions pipeline in
`.github/workflows/android-port.yml` builds:

- `crates/core` — upstream `agent-desktop-core`, unmodified.
- `crates/android` — the new Android platform adapter (bridge client).
- `src` — the upstream `agent-desktop` binary crate, with the Android
  adapter wiring. Its manifest in this tree keeps only the unix + android
  target dependencies; the macOS/Windows/Linux adapter crates are not part
  of this Linux-runner workspace.
- `companion-app` — the Kotlin bridge app (AccessibilityService +
  NotificationListener + local-socket bridge server).
- `Cargo.toml` — a trimmed workspace naming the two port crates plus the
  binary crate (`src`). The full upstream workspace also names the
  macOS/Windows/Linux adapters, which by upstream's own rule compile only
  on their own OS. The canonical full tree (all platforms, with the
  Android wiring in `src/`) is maintained with the port project.

Pipeline: the rust job runs `cargo check -p agent-desktop-android --target
aarch64-linux-android` with an NDK linker, then links the real CLI with
`cargo build -p agent-desktop --target aarch64-linux-android` and uploads
it as the `agent-desktop-cli-aarch64` artifact (a bionic aarch64 ELF that
runs in Termux). The `apk` job runs `gradle assembleDebug` for the
companion app and uploads the debug APK as a workflow artifact.

Signing: every debug APK is signed with ONE fixed project debug keystore
(standard throwaway debug credentials), so successive APK builds install
over each other on a device without an uninstall. The keystore travels as
`companion-app/debug.keystore.b64` (base64 text, because the push route
carries text only); the workflow decodes it to
`companion-app/debug.keystore` before the Gradle build, and the app's
`fixedDebug` signing config points at that file. Debug signing only;
nothing here is published.
