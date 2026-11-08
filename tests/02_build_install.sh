#!/usr/bin/env bash
# Builds the debug APK, installs it on the TV, launches it, and checks it stays alive.
. "$(dirname "$0")/lib.sh"

check "TV connected" tv_ready
(cd "$REPO_DIR" && ./gradlew :app:assembleDebug -q) > "$OUT/build.txt" 2>&1
check "assembleDebug succeeds (see $OUT/build.txt)" test -f "$REPO_DIR/app/build/outputs/apk/debug/app-debug.apk"
check "APK installs" sh -c "adb -s '$S' install -r '$REPO_DIR/app/build/outputs/apk/debug/app-debug.apk' | grep -q Success"
launch_fresh
check "app process is running after launch" pid_alive
check "no crash or decoder errors during startup" check_no_crash startup
save_logcat startup
summary
