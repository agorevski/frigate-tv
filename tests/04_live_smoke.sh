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

wait_for "a tile has focus" 10 focus_known
before="$(focus_bounds)"
key KEYCODE_DPAD_RIGHT 2
after="$(focus_bounds)"
check "D-pad RIGHT moves focus to another tile" test "$before" != "$after"

# The fullscreen overlay auto-hides after 4 s, so check right after OK.
key KEYCODE_DPAD_CENTER 0.3
check "OK opens fullscreen (overlay shows controls hint)" ui_contains "switch camera"
wait_for "fullscreen overlay shows the camera name" 5 cam_name
before_fs="$(cam_name)"
screenshot fullscreen-1

key KEYCODE_DPAD_RIGHT 0.5
screenshot fullscreen-2
check "RIGHT in fullscreen switches camera ($before_fs -> $(cam_name))" test "$(cam_name)" != "$before_fs"

key KEYCODE_BACK 3
check "BACK returns to the grid" test "$(ui_count_text LIVE)" -ge 1
check "focus restored to a tile after BACK" sh -c "[ '$(focus_bounds)' != none ]"
screenshot live-after-back

check "pid still alive" pid_alive
save_logcat live
check "no crash or decoder errors during live smoke" check_no_crash live
summary
