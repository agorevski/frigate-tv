#!/usr/bin/env bash
# Adds a profile pointing at an unreachable host, through the real form, IME and D-pad.
# The device checks are that the form can be driven, and that the refusal reaches the screen.
# The refusal logic (failed test, activation refused, Home kept, nothing stored) is in
# app/src/test/java/com/frigatetv/app/logic/AppViewModelProfileTest.kt.
# Buttons are located by their label, never by key count. If a button cannot be focused,
# the script cancels the form instead of pressing anything, so nothing unverified is saved.
. "$(dirname "$0")/lib.sh"

UNREACHABLE_HOST="10.255.255.1"
PROFILE_NAME="ZZ Unreachable Test"
check "TV connected" tv_ready
launch_fresh
goto_tab settings; wait_for "Settings tab opened" 10 ui_text "Server profiles"

if focus_button "Add server profile"; then pass "focused Add server profile"; else fail "focused Add server profile"; fi
key KEYCODE_DPAD_CENTER 2
check "form opened" ui_text "New server profile"
key KEYCODE_DPAD_CENTER 2                     # open the keyboard
key KEYCODE_DEL 1                             # the TV keyboard may have typed its highlighted key
type_text "$PROFILE_NAME"
key KEYCODE_ENTER 1                           # IME Next -> host
type_text "$UNREACHABLE_HOST"
key KEYCODE_ENTER 1; key KEYCODE_ENTER 1; key KEYCODE_ENTER 1   # port, rtsp, scheme

if focus_button "Test connection"; then pass "focused Test connection"; else fail "focused Test connection"; fi
key KEYCODE_DPAD_CENTER 1
for i in $(seq 1 15); do ui_contains "Cannot reach server" && break; sleep 2; done
check "Test connection reports unreachable server" ui_contains "Cannot reach server"
screenshot profile-test-failed

if focus_button "Save & activate"; then
  key KEYCODE_DPAD_CENTER 1
  for i in $(seq 1 15); do ui_contains "Keeping the current profile" && break; sleep 2; done
  check "Save & activate refuses and explains why" ui_contains "Keeping the current profile"
else
  fail "focused Save & activate (form cancelled instead)"
  key KEYCODE_BACK 1
fi
screenshot profile-activate-refused
save_logcat profile
check "no crash during profile flow" check_no_crash profile
summary
