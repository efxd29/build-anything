# agent-desktop Android port — CI tree

Private build repository for the Android port of vercel-labs/agent-desktop
v1.3.1. It carries exactly what the GitHub Actions pipeline in
`.github/workflows/android-port.yml` builds:

- `crates/core` — upstream `agent-desktop-core`, unmodified.
- `crates/android` — the new Android platform adapter (bridge client).
- `companion-app` — the Kotlin bridge app (AccessibilityService +
  NotificationListener + local-socket bridge server).
- `Cargo.toml` — a trimmed workspace naming only the two crates above. The
  full upstream workspace also names the macOS/Windows/Linux adapters, which
  by upstream's own rule compile only on their own OS, so they are not part
  of this Linux-runner workspace. The canonical full tree (all platforms,
  with the Android wiring in `src/`) is maintained with the port project.

Pipeline: `rust-check` runs `cargo check -p agent-desktop-android --target
aarch64-linux-android` with an NDK linker; `apk` runs
`gradle assembleDebug` for the companion app and uploads the debug APK as a
workflow artifact. Debug signing only; nothing here is published.
