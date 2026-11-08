#!/usr/bin/env bash
# Shared helpers for device tests. Source this file; do not execute it directly.
# Connection details come from the repo-root .env file (see .env.example).

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$TESTS_DIR/.." && pwd)"

if [ ! -f "$REPO_DIR/.env" ]; then
  echo "Missing $REPO_DIR/.env. Copy .env.example to .env and fill it in." >&2
  exit 2
fi
set -a; . "$REPO_DIR/.env"; set +a

# JDK 17: keep an existing JAVA_HOME; otherwise the first JDK 17 found (the default java may be newer).
if [ -z "${JAVA_HOME:-}" ]; then
  for h in "$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p')" \
           /usr/lib/jvm/java-17-openjdk-amd64 \
           /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
    if [ -x "$h/bin/java" ] && "$h/bin/java" -version 2>&1 | grep -q 'version "17[.]'; then
      export JAVA_HOME="$h"; break
    fi
  done
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

# Android SDK: keep an existing ANDROID_HOME; otherwise the first SDK found. Put adb on PATH if missing.
if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools; do
    [ -d "$d" ] && { export ANDROID_HOME="$d"; break; }
  done
fi
if ! command -v adb >/dev/null 2>&1 && [ -x "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" ]; then
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
fi

PKG=com.frigatetv.app
ACTIVITY=$PKG/.MainActivity
S="${TV_ADB_SERIAL:?TV_ADB_SERIAL is not set in .env}"
OUT="${TEST_OUT:-$TESTS_DIR/out/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); echo "PASS $*"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL $*"; }

# check "description" command args...  -> PASS if the command exits 0
check() {
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi
}

summary() {
  echo "== $PASS passed, $FAIL failed (logs: $OUT)"
  [ "$FAIL" -eq 0 ]
}

tv() { adb -s "$S" "$@"; }

# Connects to the TV if needed. Returns non-zero if the device is not in the 'device' state.
tv_ready() {
  adb connect "$S" >/dev/null 2>&1
  [ "$(adb -s "$S" get-state 2>/dev/null)" = "device" ]
}

# Raw UI hierarchy of the foreground screen.
# uiautomator can fail when a previous dump is still registered; retry instead of reading nothing.
ui_xml() {
  local i xml
  for i in 1 2 3 4; do
    tv shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    xml="$(tv shell cat /sdcard/ui.xml 2>/dev/null)"
    [ -n "$xml" ] && { printf '%s' "$xml"; return 0; }
    sleep 1
  done
  return 0
}

# Does the screen contain a node whose text equals / contains the given string?
ui_text() { ui_xml | grep -q "text=\"$1\""; }
ui_contains() { ui_xml | grep -q -- "$1"; }
ui_count_text() { ui_xml | grep -o "text=\"$1\"" | wc -l | tr -d ' '; }

# Bounds "[x1,y1][x2,y2]" of the focused node, or "none".
focus_bounds() {
  ui_xml | python3 -c '
import sys, re
x = sys.stdin.read()
m = re.findall(r"focused=\"true\"[^>]*bounds=\"(\[[^\"]*\])\"", x) or re.findall(r"bounds=\"(\[[^\"]*\])\"[^>]*focused=\"true\"", x)
print(m[0] if m else "none")'
}

key() { tv shell input keyevent "$1"; sleep "${2:-1.2}"; }
type_text() { tv shell input text "$1"; sleep 1; }
screenshot() { tv exec-out screencap -p > "$OUT/$1.png"; }

pid_alive() { [ -n "$(tv shell pidof $PKG 2>/dev/null | tr -d '\r')" ]; }

# Fresh process, focus on the default camera, screen on.
# wait_for "description" timeout_seconds command args...  -> polls until the command succeeds
wait_for() {
  local desc="$1" limit="$2"; shift 2
  local i
  for i in $(seq 1 "$limit"); do
    "$@" >/dev/null 2>&1 && { pass "$desc"; return 0; }
    sleep 1
  done
  fail "$desc"; return 1
}

launch_fresh() {
  tv shell am force-stop $PKG
  tv logcat -c
  tv shell am start -n $ACTIVITY >/dev/null
  sleep "${1:-20}"
}

# Errors that indicate a crash or decoder failure since the last logcat -c.
# Only FATAL blocks whose stack trace mentions the app count. uiautomator's own crashes are ignored.
crash_or_decoder_errors() {
  tv logcat -d | python3 -c '
import sys
lines = sys.stdin.read().splitlines()
blocks, cur = [], None
for line in lines:
    if "FATAL EXCEPTION" in line:
        cur = [line]; blocks.append(cur)
    elif cur is not None and "AndroidRuntime" in line:
        cur.append(line)
    else:
        cur = None
for b in blocks:
    if any("com.frigatetv" in l for l in b):
        print(b[0])
for line in lines:
    if "NO_MEMORY" in line or "DECODER_INIT_FAILED" in line or "ANR in com.frigatetv" in line:
        print(line)'
}
check_no_crash() {
  local errs
  errs="$(crash_or_decoder_errors)"
  echo "$errs" > "$OUT/errors-${1:-run}.txt"
  [ -z "$errs" ]
}

save_logcat() { tv logcat -d > "$OUT/logcat-${1:-run}.txt"; }

# Tab row (y 118-214) x-positions, verified on the Google TV Streamer.
TAB_X_LIVE=32
TAB_X_EVENTS=198
TAB_X_RECORDINGS=409
TAB_X_SETTINGS=698

# Moves focus up to the tab row, then left/right to the named tab, and presses OK.
goto_tab() {
  local target
  case "$1" in
    live) target=$TAB_X_LIVE ;;
    events) target=$TAB_X_EVENTS ;;
    recordings) target=$TAB_X_RECORDINGS ;;
    settings) target=$TAB_X_SETTINGS ;;
    *) echo "unknown tab $1" >&2; return 1 ;;
  esac
  local i b x
  for i in $(seq 1 15); do
    b="$(focus_bounds)"
    case "$b" in *",118]"*) break ;; esac
    key KEYCODE_DPAD_UP 1
  done
  for i in $(seq 1 6); do
    b="$(focus_bounds)"
    x="$(echo "$b" | sed -E 's/^\[([0-9]+),.*/\1/')"
    [ "$x" = "$target" ] && break
    if [ -n "$x" ] && [ "$x" -lt "$target" ] 2>/dev/null; then key KEYCODE_DPAD_RIGHT 1; else key KEYCODE_DPAD_LEFT 1; fi
  done
  key KEYCODE_DPAD_CENTER 2
}

# Bounds of the clickable element that contains a text node with exactly this label, or "none".
# FocusButton draws a clickable Box with a Text child, so walk up to the clickable ancestor.
button_bounds() {
  ui_xml | python3 -c '
import sys, xml.etree.ElementTree as ET
label = sys.argv[1]
root = ET.fromstring(sys.stdin.read())
parent = {c: p for p in root.iter() for c in p}
for node in root.iter("node"):
    if node.get("text") == label:
        cur = node
        while cur is not None and cur.get("clickable") != "true":
            cur = parent.get(cur)
        if cur is not None:
            print(cur.get("bounds")); sys.exit(0)
print("none")' "$1"
}

# Moves focus with the D-pad until the named button has focus. The list scrolls as focus moves,
# so the target's on-screen bounds are re-read on every step. Direction-aware: align within the
# row first, then move up or down. Returns non-zero without pressing OK if it cannot reach it.
focus_button() {
  local label="$1" target cur i
  for i in $(seq 1 25); do
    target="$(button_bounds "$label")"
    [ "$target" = "none" ] && return 1
    cur="$(focus_bounds)"
    [ "$cur" = "$target" ] && return 0
    [ "$cur" = "none" ] && { key KEYCODE_DPAD_DOWN 0.8; continue; }
    local cx cy tx ty
    cx="$(echo "$cur" | sed -E 's/^\[([0-9]+),([0-9]+).*/\1/')"; cy="$(echo "$cur" | sed -E 's/^\[([0-9]+),([0-9]+).*/\2/')"
    tx="$(echo "$target" | sed -E 's/^\[([0-9]+),([0-9]+).*/\1/')"; ty="$(echo "$target" | sed -E 's/^\[([0-9]+),([0-9]+).*/\2/')"
    if [ "$cy" = "$ty" ]; then
      if [ "$cx" -lt "$tx" ]; then key KEYCODE_DPAD_RIGHT 0.8; else key KEYCODE_DPAD_LEFT 0.8; fi
    elif [ "$cy" -lt "$ty" ]; then key KEYCODE_DPAD_DOWN 0.8
    else key KEYCODE_DPAD_UP 0.8
    fi
  done
  return 1
}
