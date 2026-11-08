#!/usr/bin/env bash
# Live grid: streams come up, D-pad moves focus, OK opens fullscreen, LEFT/RIGHT switch, BACK returns.
. "$(dirname "$0")/lib.sh"

check "TV connected" tv_ready
launch_fresh 25
check "app running" pid_alive
live_tiles_ready() { [ "$(ui_count_text LIVE)" -ge 4 ]; }
cam_name() { ui_xml | grep -oE 'text="(Front|Back|Mailbox|Driveway|Reolink Camera)"' | head -1; }
focus_known() { [ "$(focus_bounds)" != none ]; }
wait_for "at least 4 tiles show LIVE (streams connected)" 40 live_tiles_ready
screenshot live-grid

# Key presses sent while decoders are still starting are dropped, so wait for the streams to settle.
none_connecting() { [ "$(ui_count_text CONNECTING)" -eq 0 ]; }
wait_for "streams have settled (no tile still connecting)" 60 none_connecting
wait_for "a tile has focus" 10 focus_known
# D-pad RIGHT must move focus. Retry once: an early key press can be dropped while streams start.
before="$(focus_bounds)"
key KEYCODE_DPAD_RIGHT 2
[ "$(focus_bounds)" = "$before" ] && key KEYCODE_DPAD_RIGHT 2
check "D-pad RIGHT moves focus to another tile" test "$before" != "$(focus_bounds)"

# The fullscreen overlay auto-hides after 4 s, so check right after OK.
key KEYCODE_DPAD_CENTER 0.3
check "OK opens fullscreen (overlay shows controls hint)" ui_contains "switch camera"
# The name is only on screen while the overlay is visible; sample it for a few seconds.
sample_cam_name() { for i in $(seq 1 10); do n="$(cam_name)"; [ -n "$n" ] && { echo "$n"; return 0; }; sleep 0.3; done; return 1; }
before_fs="$(sample_cam_name)" || before_fs=""
screenshot fullscreen-1

key KEYCODE_DPAD_RIGHT 0.5
screenshot fullscreen-2
after_fs="$(sample_cam_name)" || after_fs=""
check "RIGHT in fullscreen switches camera ($before_fs -> $after_fs)" test -n "$before_fs" -a -n "$after_fs" -a "$before_fs" != "$after_fs"

key KEYCODE_BACK 3
check "BACK returns to the grid" test "$(ui_count_text LIVE)" -ge 1
check "focus restored to a tile after BACK" sh -c "[ '$(focus_bounds)' != none ]"
screenshot live-after-back

check "pid still alive" pid_alive
save_logcat live
check "no crash or decoder errors during live smoke" check_no_crash live
summary
