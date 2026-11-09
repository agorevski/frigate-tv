#!/usr/bin/env bash
# Event browser (instructions §8) and recordings/timeline (§9), driven with the D-pad only.
# Filters, cards and buttons are located by their on-screen text. Playback is judged by
# whether the video region changes between two screenshots, not by a fixed delay.
. "$(dirname "$0")/lib.sh"

# Per visible card: time|label|camera|thumbnail spread. Pass "thumbs" to also take a screenshot.
# Placeholders are flat colour (spread about 0); a decoded snapshot has detail. Without "thumbs"
# the spread is "-". Each call costs one UI dump, which is the slow part of the device.
event_cards() {
  local png=""
  ui_xml > "$OUT/events-ui.xml"
  if [ "${1:-}" = thumbs ]; then
    tv exec-out screencap -p > "$OUT/events-list.png"
    png="$OUT/events-list.png"
  fi
  python3 - "$OUT/events-ui.xml" "$png" <<'PY'
import sys, re, xml.etree.ElementTree as ET
from PIL import Image, ImageStat
TIME = re.compile(r"[A-Z][a-z]{2} \d{1,2}, \d{2}:\d{2}:\d{2}$")
root = ET.fromstring(open(sys.argv[1], encoding="utf-8").read())
img = Image.open(sys.argv[2]).convert("L") if sys.argv[2] else None
parent = {c: p for p in root.iter() for c in p}
seen = set()
for node in root.iter("node"):
    t = node.get("text") or ""
    if not TIME.match(t) or t in seen:
        continue
    cur = node
    while cur is not None and cur.get("clickable") != "true":
        cur = parent.get(cur)
    if cur is None:
        continue
    seen.add(t)
    texts = [n.get("text") for n in cur.iter("node") if n.get("text") and n.get("text") != "No preview"]
    spread = "-"
    if img is not None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", cur.get("bounds")))
        crop = img.crop((x1, y1, x2, min(y1 + (x2 - x1) * 9 // 16, y2)))
        spread = f"{ImageStat.Stat(crop).stddev[0]:.1f}"
    print(f"{t}|{texts[0]}|{texts[1] if len(texts) > 1 else ''}|{spread}")
PY
}

empty_message() { ui_contains "No events for this filter."; }

# Every visible card has a camera name and a real thumbnail.
cards_have_thumbs_and_cameras() {
  local rows n bad
  rows="$(event_cards thumbs)"
  n="$(echo "$rows" | grep -c .)"
  [ "$n" -ge 1 ] || return 1
  bad="$(echo "$rows" | awk -F'|' '$3 == "" || $4 + 0 < 8' | grep -c .)"
  [ "$bad" -eq 0 ]
}

cards_present() { [ "$(event_cards | grep -c .)" -ge 1 ]; }

# Two screenshots a moment apart differ in the video area, so playback is advancing.
frames_change() {
  tv exec-out screencap -p > "$OUT/$1-a.png"
  sleep 1.5
  tv exec-out screencap -p > "$OUT/$1-b.png"
  python3 -c '
import sys
from PIL import Image, ImageChops, ImageStat
a = Image.open(sys.argv[1]).convert("L")
b = Image.open(sys.argv[2]).convert("L")
mean = ImageStat.Stat(ImageChops.difference(a, b)).mean[0]
print(f"frame difference {mean:.2f}")
sys.exit(0 if mean > 0.5 else 1)' "$OUT/$1-a.png" "$OUT/$1-b.png" | tee -a "$OUT/playback-frames.txt"
}

# "target focus hop vhop" from one UI dump: bounds of the label's clickable ancestor, bounds of the focused
# node, the signed number of D-pad presses along their shared row (hop), and between their rows (vhop).
# Zero means no batching is possible.
bounds_pair() {
  python3 -c '
import sys, re, xml.etree.ElementTree as ET
label = sys.argv[1]
root = ET.fromstring(sys.stdin.read())
parent = {c: p for p in root.iter() for c in p}
nums = lambda b: list(map(int, re.findall(r"\d+", b)))
focus = next((n.get("bounds") for n in root.iter("node") if n.get("focused") == "true"), "none")
target = "none"
for n in root.iter("node"):
    if n.get("text") == label:
        cur = n
        while cur is not None and cur.get("clickable") != "true":
            cur = parent.get(cur)
        if cur is not None:
            target = cur.get("bounds"); break
hop = vhop = 0
if focus != "none" and target != "none":
    fx, fy = nums(focus)[:2]; tx, ty = nums(target)[:2]
    clickable = [nums(n.get("bounds")) for n in root.iter("node") if n.get("clickable") == "true"]
    if fy == ty:
        row = sorted({b[0] for b in clickable if b[1] == fy})
        if fx in row and tx in row:
            hop = row.index(tx) - row.index(fx)
    else:
        rows = sorted({b[1] for b in clickable})
        if fy in rows and ty in rows:
            vhop = rows.index(ty) - rows.index(fy)
print(target, focus, hop, vhop)' "$1"
}

# Same D-pad logic as lib.sh focus_button, with one UI dump per round instead of two.
# Presses along a row or down a column are sent together, then the next round re-reads the UI to verify.
focus_to() {
  local label="$1" i target cur hop vhop cx cy tx ty n dir
  for i in $(seq 1 25); do
    read -r target cur hop vhop < <(ui_xml | bounds_pair "$label")
    [ "$target" = none ] && return 1
    [ "$cur" = "$target" ] && return 0
    [ "$cur" = none ] && { key KEYCODE_DPAD_DOWN 0.6; continue; }
    cx="$(echo "$cur" | sed -E 's/^\[([0-9]+),([0-9]+).*/\1/')"; cy="$(echo "$cur" | sed -E 's/^\[([0-9]+),([0-9]+).*/\2/')"
    tx="$(echo "$target" | sed -E 's/^\[([0-9]+),([0-9]+).*/\1/')"; ty="$(echo "$target" | sed -E 's/^\[([0-9]+),([0-9]+).*/\2/')"
    if [ "$cy" = "$ty" ]; then
      if [ "$hop" -gt 0 ]; then n=$hop; dir=KEYCODE_DPAD_RIGHT; else n=$((-hop)); dir=KEYCODE_DPAD_LEFT; fi
      [ "$n" -eq 0 ] && { if [ "$cx" -lt "$tx" ]; then dir=KEYCODE_DPAD_RIGHT; else dir=KEYCODE_DPAD_LEFT; fi; n=1; }
      for _ in $(seq 1 "$n"); do key "$dir" 0.3; done
    else
      if [ "$cy" -lt "$ty" ]; then dir=KEYCODE_DPAD_DOWN; n=$vhop; else dir=KEYCODE_DPAD_UP; n=$((-vhop)); fi
      [ "$n" -le 0 ] && n=1
      for _ in $(seq 1 "$n"); do key "$dir" 0.4; done
    fi
  done
  return 1
}

# Focus and press OK on a labelled control; fails the check if it cannot be focused.
press_label() { focus_to "$1" && key KEYCODE_DPAD_CENTER 1 || fail "focused $1"; }

focus_on_label() {
  local target cur hop
  read -r target cur hop < <(ui_xml | bounds_pair "$1")
  [ "$cur" != none ] && [ "$cur" = "$target" ]
}

# The timeline is the only focusable element spanning most of the width.
focus_on_timeline() {
  local cur
  cur="$(ui_xml | bounds_pair "__none__" | cut -d' ' -f2)"
  [ "$cur" != none ] || return 1
  python3 -c 'import re,sys; x1,y1,x2,y2=map(int,re.findall(r"\d+",sys.argv[1])); sys.exit(0 if x2-x1>1000 else 1)' "$cur"
}

top_activity() { tv shell dumpsys activity activities | grep -m1 -o 'topResumedActivity=ActivityRecord{[^ ]* u0 [^ ]*' | sed 's/.* u0 //'; }

# Google TV sometimes covers a fresh launch with its "Choose an account" screen. BACK dismisses it
# without choosing an account, then the app is started again. Waits until the app UI is in front.
launch_app() {
  local attempt j top
  for attempt in 1 2 3; do
    tv shell am force-stop $PKG; tv logcat -c
    tv shell am start -n $ACTIVITY >/dev/null
    for j in $(seq 1 12); do
      top="$(top_activity)"
      case "$top" in
        $PKG/*) app_ui_ready && return 0 ;;
        *ProfileChooser*) key KEYCODE_BACK 1; tv shell am start -n $ACTIVITY >/dev/null ;;
      esac
      sleep 1
    done
  done
  fail "app UI appears after launch"; return 1
}

check "TV connected" tv_ready
launch_app

# ---- §8 Event browser -------------------------------------------------------------------
press_label Events
wait_for "Events list shows cards with camera names and thumbnails" 25 cards_have_thumbs_and_cameras
screenshot events-list
check "pid alive on events list" pid_alive

# Vehicle filter: this server has no vehicle events in range, so the list must empty out.
press_label Vehicles
wait_for "Vehicles filter shows the empty-state message (no cards)" 20 empty_message
screenshot events-vehicles
check "pid alive after Vehicles filter" pid_alive

# Person filter: which labels each filter keeps is a unit test (EventLabelFilterTest). Here the list must repopulate.
press_label People
wait_for "People filter repopulates the event list" 20 cards_present
screenshot events-people
check "pid alive after People filter" pid_alive

# Open the first person card, check the detail screen and that the clip plays.
first_time="$(event_cards | head -1 | cut -d'|' -f1)"
press_label "$first_time"
detail_ready() {
  local x
  x="$(ui_xml)"
  grep -q "$first_time" <<< "$x" && grep -q "Press BACK to return to the list." <<< "$x" \
    && ! grep -q "No recording available" <<< "$x" && ! grep -q "No clip or snapshot" <<< "$x"
}
wait_for "Event detail shows the opened event and its clip (no fallback text)" 15 detail_ready
wait_for "Event clip playback is advancing" 20 frames_change events-playing
screenshot events-detail
check "pid alive during event playback" pid_alive

key KEYCODE_BACK 2
wait_for "Back returns to the event list with focus on the opened card" 10 focus_on_label "$first_time"
check "pid alive after returning from detail" pid_alive

# ---- §9 Recordings and timeline ----------------------------------------------------------
press_label Recordings
wait_for "Recordings timeline loads" 30 ui_text "Play from here"
press_label Driveway
press_label Yesterday
# Past days start at noon, so the big clock reads 12:00:00 once the new day has loaded.
wait_for "Yesterday loads the timeline at 12:00:00" 30 ui_text "12:00:00"
screenshot rec-yesterday
check "pid alive after camera and day selection" pid_alive

press_label "Play from here"
wait_for "Playback view names the camera and start time" 15 ui_contains "Playing Driveway from 12:00:00"
wait_for "Recorded playback is advancing" 25 frames_change rec-playing
screenshot rec-playing
check "pid alive during recorded playback" pid_alive

key KEYCODE_BACK 2
wait_for "Back returns to the timeline" 10 ui_text "Play from here"
wait_for "Back restores focus to the timeline" 10 focus_on_timeline

save_logcat events-recordings
check "no crash or decoder errors on events and recordings" check_no_crash events-recordings
summary
