#!/usr/bin/env bash
# Runs every device and live-server script in order and writes all output to tests/out/<timestamp>/.
# Checks that need no device or server (API contract, profile and recovery logic, event filters)
# are JVM unit tests and do not run here: ./gradlew :app:testDebugUnitTest.
# Usage: tests/run_all.sh [script-name-prefix ...]   e.g. tests/run_all.sh 04 05
set -u
set -o pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
export TEST_OUT="$DIR/out/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$TEST_OUT"
status=0
for script in "$DIR"/[0-9][0-9]_*.sh; do
  name="$(basename "$script")"
  if [ "$#" -gt 0 ]; then
    match=0; for p in "$@"; do case "$name" in "$p"*) match=1 ;; esac; done
    [ "$match" -eq 1 ] || continue
  fi
  echo "### $name"
  start=$SECONDS
  if bash "$script" 2>&1 | tee "$TEST_OUT/${name%.sh}.log"; then
    echo "### $name OK ($((SECONDS - start))s)"
  else
    echo "### $name FAILED ($((SECONDS - start))s)"; status=1
  fi
done
echo "Total: $SECONDS s. Results: $TEST_OUT"
exit $status
