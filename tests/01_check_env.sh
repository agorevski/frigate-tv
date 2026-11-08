#!/usr/bin/env bash
# Verifies toolchain, .env, TV connection, and Frigate reachability.
. "$(dirname "$0")/lib.sh"

check "JDK 17 on PATH" sh -c 'java -version 2>&1 | grep -q "version \"17[.]"'
check "Android SDK platform 35 installed (${ANDROID_HOME:-ANDROID_HOME unset})" test -d "${ANDROID_HOME:-/nonexistent}/platforms/android-35"
check "Gradle wrapper present" test -x "$REPO_DIR/gradlew"
check ".env defines FRIGATE_HOST" test -n "${FRIGATE_HOST:-}"
check "TV reachable over ADB ($S)" tv_ready
check "Frigate API answers ($FRIGATE_HOST:$FRIGATE_PORT)" \
  curl -sf -m 8 "http://$FRIGATE_HOST:$FRIGATE_PORT/api/version"
summary
