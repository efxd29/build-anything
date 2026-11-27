---
name: agent-desktop-android
description: Operate the Android phone you run on, through the agent-desktop CLI and its bridge APK — see the screen as a UI tree, find elements, tap, type, launch apps and take screenshots. Use whenever the user asks you to do something on this phone's screen, or to check what is on it.
---

# agent-desktop-android

You run inside Termux on this Android phone. The `agent-desktop` CLI (`~/agent-desktop`) talks to a companion bridge app (AccessibilityService) that reads the UI tree and performs actions on the device. You are the executor: run the commands yourself with the bash tool. Never ask the user to paste commands — that is the whole point of this skill.

## Before you act

- Check the bridge is reachable: `~/agent-desktop status`. It must answer `ok: true` with `platform: android` and `accessibility.state: granted`. If it answers `Permission denied (os error 13)`, the bridge service is up but Android is walling the connection — report that and stop; do not retry in a loop. If it answers connection refused, the bridge app is not running: tell the user to check that the Agent Desktop Bridge app is installed and its AccessibilityService is enabled, then stop.

- The binary is `~/agent-desktop`. The bridge app is installed once and updates over itself; you never need to reinstall it.

## The loop

- See the screen first: `~/agent-desktop snapshot -i --compact`. Prefer `find` when you know the target: `~/agent-desktop find --role <role> --name "<name>" --exact --first`. Prefer `find` with `--limit 2` when you must prove a match is unique before acting on it.

- Act with the qualified ref exactly as returned, e.g. `~/agent-desktop click @s2skvwrokbeqre:e1`. Refs are valid for one snapshot only. Never invent or shorten a ref, never fill in a placeholder.

- On `STALE_REF`, re-run the find and use the fresh ref. On `AMBIGUOUS_TARGET`, choose a target that is unique in the window instead of repeating the ref.

- Retry or substitute an action only when its disposition says `delivery: not_delivered` and `retry: safe`. Any other delivery value means the action may have landed; verify with a fresh snapshot before repeating anything.

- Verify every action with a fresh snapshot or `get`/`is` before reporting success. Report what you did in plain words: what you tapped, typed, or opened, and what the screen shows now.

## Commands

- Proven on this device: `status`, `snapshot`, `find`, `click`.
- Built but not yet proven on this device — treat first uses as tests and report the exact result: `launch` (open an app by label or package), `type` / `set-value` (enter text into a field), `scroll`, `screenshot`.
- Desktop-only commands (window move/resize/minimize/maximize, hover, right-click, mouse-move) honestly return NOT_SUPPORTED on Android. Do not work around that.

## Output hygiene

- A full snapshot can be very large. Prefer `find`, `--first`, `--limit`, `-i`, and `--compact`. Never paste a whole tree into the chat; summarize what you found and act on it.

## Safety

- Read first, act second. Never delete, send, purchase, or change an account setting without the user explicitly asking for that exact action in this session. If an action is destructive or irreversible, say what will happen and wait for a yes.
- The phone's UI is the user's. Do not open banking, payment, or messaging apps, and do not send messages on their behalf, unless they asked for exactly that.
