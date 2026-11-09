#!/usr/bin/env bash
# Profile lifecycle and network recovery (instructions.md sections 10-12, scenarios 14-19):
#  1. add a second profile for the real Frigate server (host and ports from .env) and activate it
#  2. switch back to Home
#  3. edit that profile to an unreachable port; the save must be refused and the form stays open
#  4. delete the profile
#  5. turn Wi-Fi off on the TV for 40 s, then back on; expect the ADB link to drop and return, and
#     the app to reconnect with LIVE tiles
# Everything is located by label, and each wait polls the UI instead of sleeping.
# The profile logic (refused saves, what is stored, activation, delete, relaunch, Offline and
# reconnect state) is unit-tested in app/src/test/java/com/frigatetv/app/logic/AppViewModelProfileTest.kt.
# This script keeps what needs the device: the IME and form, the live streams, and the real Wi-Fi outage.
# The script does not check the ADB transport. It turns Wi-Fi off whenever the TV is reachable,
# including over wireless ADB (host:port serial, the project's normal workflow), which drops the
# ADB link for the outage. The outage is scheduled on the TV, so Wi-Fi comes back on even if this
# script or the link is gone.
. "$(dirname "$0")/lib.sh"

: "${FRIGATE_HOST:?FRIGATE_HOST is not set in .env}"
: "${FRIGATE_PORT:?FRIGATE_PORT is not set in .env}"
: "${FRIGATE_RTSP_PORT:?FRIGATE_RTSP_PORT is not set in .env}"

PROFILE_NAME="QA-Test-Server"
HOME_NAME="Home"
BAD_PORT="9999"
WIFI_OFF=0
SKIPPED=0

enable_wifi() { tv shell 'svc wifi enable; echo rc=$?' 2>/dev/null | tr -d '\r' | grep -q 'rc=0'; }

# Re-enables Wi-Fi on the TV when it is reachable. Called after the outage and on exit,
# so the TV is never left with Wi-Fi off. The on-device timer also re-enables it on its own.
ensure_wifi_on() {
  [ "$WIFI_OFF" = 1 ] || return 0
  if tv_ready && enable_wifi; then
    WIFI_OFF=0
    echo "Wi-Fi is on (re-enabled on the TV)"
  else
    echo "WARNING: TV not reachable to re-enable Wi-Fi; the on-device timer will do it" >&2
  fi
  return 0
}
trap ensure_wifi_on EXIT
trap 'ensure_wifi_on; exit 130' INT TERM

# Header text is the strip above the tab row (bounds top < 118).
header_has() {
  ui_xml | python3 -c '
import sys, re, xml.etree.ElementTree as ET
want = sys.argv[1]
def top(n):
    m = re.match(r"\[(\d+),(\d+)\]", n.get("bounds", ""))
    return int(m.group(2)) if m else 9999
root = ET.fromstring(sys.stdin.read())
sys.exit(0 if any(n.get("text") == want and top(n) < 118 for n in root.iter("node")) else 1)' "$1"
}

# Bounds of the clickable button labelled $2 inside the profile row named $1, or "none".
# A row is the nearest container of the name that holds exactly one Edit button (every row has one).
# Works whether the list is scrolled or not, because off-screen nodes are still in the dump.
row_button_bounds() {
  ui_xml | python3 -c '
import sys, xml.etree.ElementTree as ET
name, label = sys.argv[1], sys.argv[2]
root = ET.fromstring(sys.stdin.read())
parent = {c: p for p in root.iter() for c in p}
def row_of(node):
    cur = node
    while cur is not None:
        edits = [d for d in cur.iter("node") if d.get("text") == "Edit"]
        if edits:
            return cur if len(edits) == 1 else None
        cur = parent.get(cur)
    return None
for name_node in root.iter("node"):
    if name_node.get("text") != name:
        continue
    row = row_of(name_node)
    if row is None:
        continue
    for n in row.iter("node"):
        if n.get("text") == label:
            cur = n
            while cur is not None and cur.get("clickable") != "true":
                cur = parent.get(cur)
            if cur is not None:
                print(cur.get("bounds")); sys.exit(0)
print("none")
sys.exit(0)' "$1" "$2"
}

row_present() { [ "$(row_button_bounds "$1" Edit)" != none ]; }
row_gone() { ! row_present "$1"; }
live_ready() { [ "$(ui_count_text LIVE)" -ge 1 ]; }
adb_down() { ! tv_ready; }
live_and_connected() { header_has Connected && live_ready; }
form_closed() { ! ui_text "New server profile" && ! ui_text "Edit server profile"; }

# Moves focus to the button $2 of profile row $1, re-reading its bounds each step (the list scrolls).
focus_row_button() {
  local name="$1" label="$2" target cur i cx cy tx ty
  for i in $(seq 1 25); do
    target="$(row_button_bounds "$name" "$label")"
    [ "$target" = "none" ] && return 1
    cur="$(focus_bounds)"
    [ "$cur" = "$target" ] && return 0
    [ "$cur" = "none" ] && { key KEYCODE_DPAD_DOWN 0.8; continue; }
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

# Form entry. The IME is a separate window, so it is read from dumpsys, not from the UI tree.
# Field-to-field moves use TAB (Compose focus order: Name, Host, Port, RTSP). ENTER and D-pad
# are not reliable here: ENTER skips the side-by-side Port/RTSP row, and D-pad is consumed by
# text fields and by an open keyboard. The keyboard is closed with BACK before leaving the fields.
ime_shown() { tv shell dumpsys input_method | grep -q 'mInputShown=true'; }
ime_hidden() { ! ime_shown; }
ime_hide() {
  ime_shown || return 0
  key KEYCODE_BACK 0.3
  until_true 4 ime_hidden
}

# "bounds<TAB>text" of the EditText whose label is $1, or "none".
field_info() {
  ui_xml | python3 -c '
import sys, xml.etree.ElementTree as ET
label = sys.argv[1]
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    print("none"); sys.exit(0)
for n in root.iter("node"):
    if n.get("class") == "android.widget.EditText" and any(c.get("text") == label for c in n.iter("node")):
        print(n.get("bounds") + "\t" + (n.get("text") or "")); sys.exit(0)
print("none")' "$1"
}
field_has() { [ "$(field_info "$1" | cut -f2)" = "$2" ]; }

# "kind<TAB>label" of the focused node: kind is EditText, a class name, or none.
# A field is identified by its label, because its bounds move when the keyboard opens.
focus_info() {
  ui_xml | python3 -c '
import sys, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    print("none\t"); sys.exit(0)
for n in root.iter("node"):
    if n.get("focused") == "true":
        kind = n.get("class", "").split(".")[-1]
        label = next((c.get("text") for c in n.iter("node") if c is not n and c.get("text")), "") if kind == "EditText" else ""
        print(kind + "\t" + label); sys.exit(0)
print("none\t")'
}

# Moves focus to the field labelled $1 with TAB, keeping the keyboard open.
focus_field() {
  local label="$1" info kind cur i
  for i in $(seq 1 8); do
    info="$(focus_info)"; kind="${info%%$'\t'*}"; cur="${info#*$'\t'}"
    [ "$kind" = EditText ] || return 1
    [ "$cur" = "$label" ] && return 0
    ime_shown || { key KEYCODE_DPAD_CENTER 0.3; until_true 4 ime_shown || return 1; }
    key KEYCODE_TAB 0.8
  done
  return 1
}

# Closes the keyboard, then moves focus off the text fields so D-pad navigation works again.
leave_text_fields() {
  local info kind i
  ime_hide || return 1
  for i in $(seq 1 6); do
    info="$(focus_info)"; kind="${info%%$'\t'*}"
    [ "$kind" = EditText ] || return 0
    key KEYCODE_TAB 0.8
  done
  return 1
}

# Sets the field labelled $1 to $2 and checks the value on screen; retypes once if it differs.
# The keyboard stays open afterwards so the next field can be reached with ENTER.
set_field() {
  local label="$1" value="$2" cur len attempt
  focus_field "$label" || { fail "focused $label field"; return 1; }
  ime_shown || { key KEYCODE_DPAD_CENTER 0.3; until_true 5 ime_shown || { fail "keyboard opened for $label"; return 1; }; }
  for attempt in 1 2; do
    cur="$(field_info "$label" | cut -f2)"
    len=${#cur}; [ "$len" -gt 40 ] && len=40
    tv shell "input keyevent KEYCODE_MOVE_END; n=$((len + 2)); while [ \$n -gt 0 ]; do input keyevent KEYCODE_DEL; n=\$((n - 1)); done"
    tv shell input text "$value"
    until_true 4 field_has "$label" "$value" && return 0
  done
  fail "$label holds '$(field_info "$label" | cut -f2)', expected '$value'"
  return 1
}

halt() { finish; exit 1; }

skip() {
  SKIPPED=$((SKIPPED + 1))
  echo "SKIPPED $*"
}

finish() {
  ensure_wifi_on
  screenshot network-final
  save_logcat network
  check "no crash during profile and network flow" check_no_crash network
  [ "$SKIPPED" -gt 0 ] && echo "== $SKIPPED step(s) SKIPPED (see above)"
  summary
}

abort() { fail "$1"; finish; exit 1; }

if ! tv_ready; then fail "TV connected"; summary; exit 1; fi
pass "TV connected"

# ---- Setup: open Settings and remove any leftover test profile from an earlier run.
launch_fresh
goto_tab settings; wait_for "Settings tab opened" 10 ui_text "Server profiles"
if row_present "$PROFILE_NAME"; then
  echo "Leftover '$PROFILE_NAME' profile from an earlier run; removing it"
  if [ "$(row_button_bounds "$PROFILE_NAME" Activate)" = none ]; then
    focus_row_button "$HOME_NAME" Activate && key KEYCODE_DPAD_CENTER 2
    wait_for "Home reactivated before removing the leftover profile" 30 header_has "$HOME_NAME"
  fi
  focus_row_button "$PROFILE_NAME" Delete && key KEYCODE_DPAD_CENTER 2
  wait_for "leftover test profile removed" 15 row_gone "$PROFILE_NAME"
fi

# ---- 1. Second profile for the real server, activated.
if ! focus_button "Add server profile"; then abort "focused Add server profile"; fi
key KEYCODE_DPAD_CENTER 2
wait_for "new profile form opened" 10 ui_text "New server profile"
set_field "Name" "$PROFILE_NAME" || halt
set_field "Host or IP address (e.g. 192.168.1.20)" "$FRIGATE_HOST" || halt
set_field "Port" "$FRIGATE_PORT" || halt
set_field "RTSP port" "$FRIGATE_RTSP_PORT" || halt
leave_text_fields || abort "left the text fields of the new profile form"
if focus_button "HTTP"; then key KEYCODE_DPAD_CENTER 1; else abort "focused HTTP scheme"; fi
if focus_button "Test connection"; then key KEYCODE_DPAD_CENTER 1; else abort "focused Test connection"; fi
wait_for "test connection reaches the real server" 30 ui_contains "Connected: Frigate"
if focus_button "Save & activate"; then key KEYCODE_DPAD_CENTER 1; else abort "focused Save & activate"; fi
wait_for "form closes after Save & activate" 30 form_closed
wait_for "header shows $PROFILE_NAME after activation" 10 header_has "$PROFILE_NAME"
goto_tab live
wait_for "cameras reload for $PROFILE_NAME (LIVE >= 1)" 60 live_ready
screenshot network-second-profile-live

# ---- 2. Switch back to Home.
goto_tab settings; wait_for "Settings tab reopened" 10 ui_text "Server profiles"
if focus_row_button "$HOME_NAME" Activate; then key KEYCODE_DPAD_CENTER 1; else abort "focused Home Activate"; fi
wait_for "header shows Home after switching back" 30 header_has "$HOME_NAME"
goto_tab live
wait_for "Home live grid returns (LIVE >= 1)" 60 live_ready
screenshot network-home-restored

# ---- 3. Edit the test profile to an unreachable port; the activating save must be refused.
goto_tab settings; wait_for "Settings tab reopened" 10 ui_text "Server profiles"
if ! focus_row_button "$PROFILE_NAME" Edit; then abort "focused Edit on $PROFILE_NAME"; fi
key KEYCODE_DPAD_CENTER 2
wait_for "edit form opened" 10 ui_text "Edit server profile"
set_field "Port" "$BAD_PORT" || halt
leave_text_fields || abort "left the text fields of the edit form"
if focus_button "Test connection"; then key KEYCODE_DPAD_CENTER 1; else abort "focused Test connection (edit)"; fi
wait_for "test connection reports Cannot reach server" 30 ui_contains "Cannot reach server"
if focus_button "Save & activate"; then key KEYCODE_DPAD_CENTER 1; else abort "focused Save & activate (edit)"; fi
wait_for "save refused and the current profile kept" 15 ui_contains "Keeping the current profile"
check "edit form stays open after the refused save" ui_text "Edit server profile"
screenshot network-save-refused
if focus_button "Cancel"; then key KEYCODE_DPAD_CENTER 1; else fail "focused Cancel (edit)"; fi
wait_for "edit form closed" 10 form_closed

# ---- 4. Delete the test profile.
if ! focus_row_button "$PROFILE_NAME" Delete; then abort "focused Delete on $PROFILE_NAME"; fi
key KEYCODE_DPAD_CENTER 2
wait_for "test profile removed from the list" 15 row_gone "$PROFILE_NAME"

# ---- 5. Wi-Fi loss and automatic recovery.
# The outage is scheduled on the TV, so it works over wireless ADB: the TV disables Wi-Fi,
# waits 40 s, and re-enables it, even if this script or the ADB link is gone. ADB drops during
# the outage, so the UI cannot be read then. The drop itself proves the outage; the app's
# Offline state is not visible from here because the app does not log network transitions.
goto_tab live
wait_for "Home connected before the network test" 60 live_and_connected
WIFI_OFF=1
outage_scheduled="$(tv shell "(nohup sh -c 'sleep 3; svc wifi disable; sleep 40; svc wifi enable' </dev/null >/dev/null 2>&1 &); sleep 1; (ps -A 2>/dev/null || ps) | grep '[s]leep 40'" | tr -d '\r')"
if [ -n "$outage_scheduled" ]; then
  wait_for "ADB link drops while Wi-Fi is off on the TV" 15 adb_down
  wait_for "ADB reconnects after Wi-Fi returns (retried adb connect)" 90 tv_ready
  wait_for "app returns to Connected with LIVE tiles within 90 s" 90 live_and_connected
  screenshot network-reconnected
  ensure_wifi_on
  check "Wi-Fi re-enabled on the TV (safety step)" [ "$WIFI_OFF" = 0 ]
else
  WIFI_OFF=0
  skip "network recovery: could not schedule the Wi-Fi outage on the TV (nohup or ps missing)"
fi

finish
