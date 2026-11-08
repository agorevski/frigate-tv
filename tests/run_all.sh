#!/usr/bin/env bash
# Runs every test script in order and writes all output to tests/out/<timestamp>/.
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
  if bash "$script" 2>&1 | tee "$TEST_OUT/${name%.sh}.log"; then
    echo "### $name OK"
  else
    echo "### $name FAILED"; status=1
  fi
done
echo "Results: $TEST_OUT"
exit $status
