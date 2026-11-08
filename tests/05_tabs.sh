#!/usr/bin/env bash
# Visits each tab with the D-pad and checks its key content is present and the app stays alive.
. "$(dirname "$0")/lib.sh"

check "TV connected" tv_ready
launch_fresh
goto_tab events;     wait_for "Events tab shows filters" 15 ui_text "Type"
screenshot tab-events
goto_tab recordings; wait_for "Recordings tab shows timeline controls (after load)" 30 ui_text "Play from here"
screenshot tab-recordings
goto_tab settings;   wait_for "Settings tab shows server profiles" 10 ui_text "Server profiles"
screenshot tab-settings
live_tiles_visible() { [ "$(ui_count_text LIVE)" -ge 1 ]; }
goto_tab live;       wait_for "Live tab shows the grid again" 10 live_tiles_visible
check "pid alive after visiting all tabs" pid_alive
save_logcat tabs
check "no crash or decoder errors across tabs" check_no_crash tabs
summary
