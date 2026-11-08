#!/usr/bin/env bash
# Visits each tab with the D-pad and checks its key content is present and the app stays alive.
. "$(dirname "$0")/lib.sh"

check "TV connected" tv_ready
launch_fresh 25
goto_tab events;     sleep 3; check "Events tab shows filters" ui_text "Type"
screenshot tab-events
goto_tab recordings; wait_for "Recordings tab shows timeline controls (after load)" 30 ui_text "Play from here"
screenshot tab-recordings
goto_tab settings;   sleep 2; check "Settings tab shows server profiles" ui_text "Server profiles"
screenshot tab-settings
goto_tab live;       sleep 3; check "Live tab shows the grid again" test "$(ui_count_text LIVE)" -ge 1
check "pid alive after visiting all tabs" pid_alive
save_logcat tabs
check "no crash or decoder errors across tabs" check_no_crash tabs
summary
