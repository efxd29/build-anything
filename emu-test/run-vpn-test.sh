#!/usr/bin/env bash
# Scropy VPN-freeze self-test, run inside android-emulator-runner (KVM).
# Usage: bash emu-test/run-vpn-test.sh <scropy_apk_url> [expected_sha256]
# Writes all evidence to emu-test/evidence/ (uploaded as a workflow artifact).
# Design: never `set -e` — every stage logs PASS/FAIL/BLOCKED and the run
# continues so a failure in one stage still leaves evidence for the rest.
set -u
APK_URL="${1:-}"
EXPECTED_SHA="${2:-}"
EVID="emu-test/evidence"
mkdir -p "$EVID"
LOG="$EVID/run.log"
exec > >(tee -a "$LOG") 2>&1

ts() { date '+%H:%M:%S'; }
log() { echo "[$(ts)] $*"; }
fail() { log "FAIL: $*"; }
pass() { log "PASS: $*"; }
note() { log "NOTE: $*"; }

shot() { # shot <name>
  adb exec-out screencap -p > "$EVID/$1.png" 2>/dev/null || log "shot $1 failed"
}
uidump() { # uidump <name> — writes $EVID/<name>.xml, echoes path
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$EVID/$1.xml" >/dev/null 2>&1
}
# tap_text <needle> — tap centre of first node whose text or content-desc matches
tap_text() {
  local needle="$1" dump="$EVID/ui-last.xml"
  uidump ui-last
  python3 - "$dump" "$needle" <<'PYEOF'
import sys, re, xml.etree.ElementTree as ET
path, needle = sys.argv[1], sys.argv[2].lower()
try:
    tree = ET.parse(path)
except Exception as e:
    print("DUMP_PARSE_FAIL", e); sys.exit(1)
for node in tree.iter("node"):
    text = (node.get("text") or "") + " " + (node.get("content-desc") or "")
    if needle in text.lower() and node.get("clickable") == "true":
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print(f"{(x1+x2)//2} {(y1+y2)//2}")
            sys.exit(0)
print("NOT_FOUND")
sys.exit(1)
PYEOF
}
tap_at() { # tap_at <x> <y>
  adb shell input tap "$1" "$2"
}
try_tap_text() { # try_tap_text <needle> — returns 0 if tapped
  local out
  out=$(tap_text "$1" 2>/dev/null) || { log "tap_text '$1': NOT_FOUND"; return 1; }
  if [[ "$out" == "DUMP_PARSE_FAIL"* || "$out" == "NOT_FOUND" ]]; then
    log "tap_text '$1': $out"; return 1
  fi
  log "tap_text '$1' -> tapping $out"
  tap_at $out
  return 0
}
wd() { # wd — print watchdog.txt (needs adb root)
  adb shell cat /sdcard/Android/data/tech.devline.scropy_ui/files/watchdog.txt 2>/dev/null \
    || echo "(watchdog.txt unreadable/missing)"
}
frames_rendered() { # total frames rendered by Scropy (gfxinfo), -1 on failure
  local n
  n=$(adb shell dumpsys gfxinfo tech.devline.scropy_ui 2>/dev/null | grep -i "Total frames rendered" | grep -o '[0-9]*' | head -1)
  echo "${n:--1}"
}
top_activity() {
  adb shell dumpsys activity activities 2>/dev/null \
    | grep -m1 "topResumedActivity" | sed 's/^ *//'
}
vpn_up() { # 0 if tun0 exists
  adb shell ip addr show tun0 2>/dev/null | grep -q "inet "
}

log "=== Scropy VPN-freeze test started $(date) ==="
log "APK_URL=$APK_URL"

# ---------- 1. device ready ----------
adb wait-for-device
adb root >/dev/null 2>&1
adb wait-for-device
pass "device online: $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r') / $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

# ---------- 2. download + install APKs ----------
cd /tmp
curl -sL -o scropy.apk "$APK_URL" || fail "scropy download failed"
ACTUAL_SHA=$(sha256sum scropy.apk | cut -d' ' -f1)
log "scropy.apk sha256=$ACTUAL_SHA size=$(stat -c%s scropy.apk)"
if [[ -n "$EXPECTED_SHA" && "$ACTUAL_SHA" != "$EXPECTED_SHA" ]]; then
  fail "sha256 mismatch (expected $EXPECTED_SHA) — refusing to install"
  echo "SHA_MISMATCH" > "$OLDPWD/$EVID/VERDICT.md"
  exit 1
fi
V2RAY_URL="https://github.com/2dust/v2rayNG/releases/download/2.3.15/v2rayNG_2.3.15_x86_64.apk"
curl -sL -o v2ray.apk "$V2RAY_URL" || fail "v2rayNG download failed"
log "v2ray.apk size=$(stat -c%s v2ray.apk)"
adb install -r scropy.apk || fail "scropy install failed"
adb install -r v2ray.apk || fail "v2rayNG install failed"
adb shell pm list packages | grep -E "scropy_ui|v2ray" || fail "packages not listed after install"
pass "both APKs installed"
cd "$OLDPWD"

# ---------- 3. push VPN profile ----------
adb push emu-test/test-vpn.conf /sdcard/test-vpn.conf || fail "conf push failed"
pass "test-vpn.conf pushed"

# ---------- 4. guest adbd into TCP mode, host reconnects ----------
adb tcpip 5555
sleep 3
RECONNECTED=0
for i in $(seq 1 12); do
  if adb connect localhost:5555 2>&1 | grep -q "connected"; then
    adb -s localhost:5555 wait-for-device 2>/dev/null
    RECONNECTED=1; break
  fi
  sleep 5
done
if [[ "$RECONNECTED" == "0" ]]; then
  fail "could not reconnect adb after tcpip — test cannot continue"
  echo "ADB_RECONNECT_FAILED" > "$EVID/VERDICT.md"
  exit 1
fi
adb root >/dev/null 2>&1
adb wait-for-device
pass "adb reconnected on localhost:5555, tcp port=$(adb shell getprop service.adb.tcp.port 2>/dev/null | tr -d '\r')"
GUEST_IP=$(adb shell ip -o addr show eth0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
log "guest IP: $GUEST_IP"
[[ -z "$GUEST_IP" ]] && GUEST_IP="10.0.2.15" && note "guest IP parse failed, assuming 10.0.2.15"

# ---------- 5. import VPN profile into v2rayNG ----------
VPN_IMPORTED=0
adb shell am start -a android.intent.action.VIEW -d file:///sdcard/test-vpn.conf -t text/plain -n com.v2ray.ang/.ui.MainActivity >/dev/null 2>&1
sleep 5
shot vpn-import-intent
uidump vpn-import-intent
if grep -qi "203.0.113.1\|test-vpn\|WireGuard\|wireguard" "$EVID/vpn-import-intent.xml" 2>/dev/null; then
  VPN_IMPORTED=1; pass "VPN profile imported via VIEW intent"
else
  note "VIEW intent did not show the profile — trying manual import flow"
  adb shell am start -n com.v2ray.ang/.ui.MainActivity >/dev/null 2>&1
  sleep 4
  # v2rayNG main screen: tap the + (add) button, then "Import config from file"
  try_tap_text "add" || try_tap_text "Add" || try_tap_text "+"
  sleep 2
  shot vpn-import-menu
  if try_tap_text "Import config from file"; then
    sleep 3
    shot vpn-import-picker
    # DocumentsUI file picker: show the file by name if visible
    try_tap_text "test-vpn.conf" && sleep 3
  fi
  uidump vpn-import-manual
  if grep -qi "203.0.113.1\|test-vpn\|WireGuard\|wireguard" "$EVID/vpn-import-manual.xml" 2>/dev/null; then
    VPN_IMPORTED=1; pass "VPN profile imported via manual flow"
  else
    fail "VPN profile import FAILED both ways — VPN toggle stage BLOCKED (will fall back to wifi toggle)"
  fi
fi

# ---------- 6. launch Scropy, connect to guest's own adbd ----------
adb shell am start -n tech.devline.scropy_ui/.StreamActivity --es host "$GUEST_IP" --ei port 5555 >/dev/null 2>&1
sleep 8
shot scropy-launch
# RSA auth dialog ("Allow USB debugging?") — accept it if present
for i in 1 2 3; do
  if try_tap_text "Allow"; then sleep 5; break; fi
  sleep 4
done
# wait for mirror: StreamActivity resumed + frames advancing
MIRROR=0
for i in $(seq 1 12); do
  TOP=$(top_activity)
  log "tick $i top: $TOP"
  if echo "$TOP" | grep -q "StreamActivity"; then
    F1=$(frames_rendered); sleep 5; F2=$(frames_rendered)
    log "frames rendered: $F1 -> $F2"
    if [[ "$F1" != "-1" && "$F2" != "-1" && "$F2" -gt "$F1" ]]; then
      MIRROR=1; break
    fi
  fi
  sleep 5
done
shot scropy-mirror
if [[ "$MIRROR" == "1" ]]; then
  pass "MIRROR ESTABLISHED — frames advancing"
else
  fail "mirror NOT established (StreamActivity not rendering / no frame advance)"
  log "--- watchdog.txt ---"; wd | tee "$EVID/watchdog-before-vpn.txt"
  log "--- logcat (scropy) ---"; adb logcat -d 2>/dev/null | grep -i "scropy" | tail -30 | tee "$EVID/logcat-nomirror.txt"
fi
log "--- watchdog.txt at mirror stage ---"; wd | tee "$EVID/watchdog-mirror.txt"

# ---------- 7. VPN ON + observe ----------
observe() { # observe <seconds> <label>
  local total="$1" label="$2" elapsed=0
  local fprev=$(frames_rendered)
  log "[$label] start frames=$fprev"
  while [[ $elapsed -lt $total ]]; do
    sleep 10; elapsed=$((elapsed+10))
    local fnow=$(frames_rendered)
    local top=$(top_activity)
    log "[$label] +${elapsed}s frames=$fnow (delta $((fnow-fprev))) top=$top vpn_up=$(vpn_up && echo yes || echo no)"
    fprev=$fnow
  done
  log "[$label] watchdog.txt now:"; wd | tee "$EVID/watchdog-$label.txt"
  shot "observe-$label"
}
if [[ "$VPN_IMPORTED" == "1" ]]; then
  adb shell am start -n com.v2ray.ang/.ui.MainActivity >/dev/null 2>&1
  sleep 4
  # start the service: tap the start FAB (content-desc varies) — try candidates
  try_tap_text "Start" || try_tap_text "start" || try_tap_text "Connect" || note "no Start button matched by text"
  sleep 4
  # VpnService consent dialog ("OK")
  try_tap_text "OK" && sleep 3
  for i in $(seq 1 6); do
    if vpn_up; then pass "VPN ON — tun0 up after ~$((i*5))s"; break; fi
    sleep 5
  done
  vpn_up && log "VPN confirmed UP" || fail "VPN did NOT come up (tun0 missing) — observe stage still runs"
  shot vpn-on
  # bring Scropy back to front WITHOUT creating a second instance
  adb shell am start -f 0x20000000 -n tech.devline.scropy_ui/.StreamActivity >/dev/null 2>&1
  sleep 3
  observe 90 vpn-on
  # ---------- 8. VPN OFF + observe recovery ----------
  adb shell am start -n com.v2ray.ang/.ui.MainActivity >/dev/null 2>&1
  sleep 4
  try_tap_text "Stop" || try_tap_text "stop" || try_tap_text "Disconnect" || note "no Stop button matched by text"
  sleep 5
  vpn_up && fail "VPN still up after stop tap" || pass "VPN OFF — tun0 gone"
  adb shell am start -f 0x20000000 -n tech.devline.scropy_ui/.StreamActivity >/dev/null 2>&1
  sleep 3
  observe 60 vpn-off
else
  # Fallback trigger: wifi off/on breaks the socket path too (different code
  # path than VPN, but still exercises the watchdog's recovery).
  note "FALLBACK: toggling guest wifi off/on instead of VPN"
  adb shell svc wifi disable
  sleep 3
  observe 60 wifi-off
  adb shell svc wifi enable
  sleep 5
  observe 60 wifi-back
fi

# ---------- 9. collect evidence + verdict ----------
adb logcat -d > "$EVID/logcat-full.txt" 2>/dev/null
wd > "$EVID/watchdog-final.txt" || true
shot final
RELAUNCHES=$(grep -c "RELAUNCH" "$EVID/watchdog-final.txt" 2>/dev/null || true)
RECOVERED=$(grep -c "CONNECTED-RECOVERED" "$EVID/watchdog-final.txt" 2>/dev/null || true)
{
  echo "# Scropy VPN-freeze test — verdict"
  echo ""
  echo "- Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Mirror established: $([[ "$MIRROR" == "1" ]] && echo YES || echo NO)"
  echo "- VPN profile imported: $([[ "$VPN_IMPORTED" == "1" ]] && echo YES || echo NO)"
  echo "- Watchdog RELAUNCH lines: $RELAUNCHES"
  echo "- Watchdog CONNECTED-RECOVERED lines: $RECOVERED"
  echo ""
  echo "## How to read this"
  echo "- If the mirror froze while the VPN was on AND RELAUNCH = 0, the"
  echo "  watchdog did not fire: the next build must drop the touch-window"
  echo "  condition and add per-tick logging."
  echo "- If RELAUNCH > 0, note the timestamps vs the VPN toggle times in"
  echo "  run.log to measure recovery speed."
  echo "- Frame deltas per 10s tick are in run.log (observe sections);"
  echo "  gfxinfo totals are the objective frame-flow signal."
  echo ""
  echo "## Files"
  echo "- run.log — full timestamped log"
  echo "- logcat-full.txt — full logcat"
  echo "- watchdog-*.txt — watchdog.txt snapshots per stage"
  echo "- *.png — screenshots per stage"
} > "$EVID/VERDICT.md"
log "=== test finished: mirror=$MIRROR vpn_imported=$VPN_IMPORTED relaunches=$RELAUNCHES recovered=$RECOVERED ==="
