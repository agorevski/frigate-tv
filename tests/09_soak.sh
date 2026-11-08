#!/usr/bin/env bash
# Scenario 20: extended simultaneous playback soak.
# Every SOAK_INTERVAL seconds: LIVE/CONNECTING tile counts, app PSS, app CPU, cumulative decoder/FATAL errors.
# Every 300 s: navigation cycle (RIGHT, OK, RIGHT, BACK) and a check that the grid is still playing.
# Env: SOAK_SECONDS (default 1800), SOAK_INTERVAL (default 60).
. "$(dirname "$0")/lib.sh"

SOAK_SECONDS="${SOAK_SECONDS:-1800}"
SOAK_INTERVAL="${SOAK_INTERVAL:-60}"
for v in "$SOAK_SECONDS" "$SOAK_INTERVAL"; do
  case "$v" in ''|*[!0-9]*) echo "SOAK_SECONDS and SOAK_INTERVAL must be whole seconds" >&2; exit 2 ;; esac
done

TICK=10          # UI and process poll period
NAV_EVERY=300
MIN_LIVE=4       # tiles that must be LIVE after startup and after each navigation cycle
LOW_LIMIT=3      # below this many LIVE tiles for longer than LOW_GRACE stops the soak
LOW_GRACE=60
CSV="$OUT/soak.csv"
SUMMARY="$OUT/soak-summary.txt"

# Always leave no streams running, even on Ctrl-C or an early stop.
trap 'tv shell am force-stop $PKG >/dev/null 2>&1' EXIT

live_ready() { [ "$(ui_count_text LIVE)" -ge "$MIN_LIVE" ]; }
pid_gone() { ! pid_alive; }
app_pid() { tv shell pidof $PKG 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

# LIVE and CONNECTING tile counts from one UI dump. Sets globals live and conn.
tile_counts() {
  local xml
  xml="$(ui_xml)"
  live="$(printf '%s' "$xml" | grep -o 'text="LIVE"' | wc -l | tr -d ' ')"
  conn="$(printf '%s' "$xml" | grep -o 'text="CONNECTING"' | wc -l | tr -d ' ')"
}

# Total PSS in kB from dumpsys meminfo, or empty.
pss_kb() {
  tv shell dumpsys meminfo $PKG 2>/dev/null | tr -d '\r' \
    | grep -E '^ *TOTAL( PSS)?:? +[0-9]+' | head -1 | grep -oE '[0-9]+' | head -1
}

# Prints "<cpu percent> <source>". Source is "top" (toybox top, single batch iteration) or
# "dumpsys-cpuinfo" (fallback); "NA na" if neither reports the process.
cpu_sample() {
  local pid="$1" cpu
  cpu="$(tv shell top -b -n 1 -d 1 -p "$pid" 2>/dev/null | tr -d '\r' | python3 -c '
import re, sys
pid = sys.argv[1]
for line in sys.stdin:
    f = line.split()
    j = next((i for i, t in enumerate(f) if re.fullmatch(r"\d+:\d\d\.\d+", t)), None)
    if j is not None and j >= 2 and f[0] == pid:
        print(f[j - 2].rstrip("%")); break' "$pid")"
  if [ -n "$cpu" ]; then echo "$cpu top"; return; fi
  cpu="$(tv shell dumpsys cpuinfo 2>/dev/null | tr -d '\r' | grep -F "$PKG" | head -1 \
    | grep -oE '^ *[0-9.]+%' | tr -d ' %')"
  if [ -n "$cpu" ]; then echo "$cpu dumpsys-cpuinfo"; else echo "NA na"; fi
}

# Prints "<total> <fatal>" from the same patterns as check_no_crash.
error_counts() {
  local errs
  errs="$(crash_or_decoder_errors)"
  echo "$(printf '%s\n' "$errs" | grep -c .) $(printf '%s\n' "$errs" | grep -cE 'FATAL EXCEPTION|ANR in com.frigatetv')"
}

# Camera switch cycle in the grid: RIGHT, OK (fullscreen), RIGHT (next camera), BACK.
nav_cycle() {
  local n="$1"
  nav_total=$((nav_total + 1))
  key KEYCODE_DPAD_RIGHT 1
  key KEYCODE_DPAD_CENTER 1
  key KEYCODE_DPAD_RIGHT 1
  key KEYCODE_BACK 1
  if check "navigation cycle $n: app alive after BACK" pid_alive \
     && wait_for "navigation cycle $n: at least $MIN_LIVE tiles LIVE after BACK" 20 live_ready; then
    nav_pass=$((nav_pass + 1))
  fi
}

start_pid=""
stop_reason=""
nav_total=0
nav_pass=0
conn_events=0
prev_conn=0
errors_total=0
fatal_total=0

echo "elapsed_s,live,connecting,pid,pss_kb,cpu_pct,cpu_source,errors_total" > "$CSV"

launch_fresh
if check "app running after launch" pid_alive; then
  start_pid="$(app_pid)"
  if wait_for "at least $MIN_LIVE tiles show LIVE within 90 s (streams connected)" 90 live_ready; then
    screenshot soak-start
    t0=$SECONDS
    next_sample=0
    next_nav=$NAV_EVERY
    low_since=""
    mid_done=0
    while :; do
      t=$((SECONDS - t0))
      [ "$t" -ge "$SOAK_SECONDS" ] && break

      pid="$(app_pid)"
      if [ -z "$pid" ]; then stop_reason="app process died at ${t}s"; echo "STOP: $stop_reason"; break; fi
      if [ "$pid" != "$start_pid" ]; then stop_reason="app pid changed $start_pid -> $pid at ${t}s (silent restart)"; echo "STOP: $stop_reason"; break; fi

      tile_counts
      if [ "$conn" -gt "$prev_conn" ]; then conn_events=$((conn_events + conn - prev_conn)); fi
      prev_conn=$conn

      if [ "$live" -lt "$LOW_LIMIT" ]; then
        [ -z "$low_since" ] && low_since=$t
        if [ $((t - low_since)) -gt "$LOW_GRACE" ]; then
          stop_reason="fewer than $LOW_LIMIT LIVE tiles for over ${LOW_GRACE}s (from ${low_since}s)"
          echo "STOP: $stop_reason"; break
        fi
      else
        low_since=""
      fi

      if [ "$t" -ge "$next_sample" ]; then
        next_sample=$((t + SOAK_INTERVAL))
        pss="$(pss_kb)"
        read -r cpu cpu_src <<< "$(cpu_sample "$pid")"
        read -r errors_total fatal_total <<< "$(error_counts)"
        echo "$t,$live,$conn,$pid,${pss:-NA},$cpu,$cpu_src,$errors_total" >> "$CSV"
        echo "t=${t}s live=$live connecting=$conn pss=${pss:-NA}kB cpu=$cpu% ($cpu_src) errors=$errors_total"
        if [ "$fatal_total" -gt 0 ]; then stop_reason="FATAL EXCEPTION or ANR in app at ${t}s"; echo "STOP: $stop_reason"; break; fi
      fi

      if [ "$mid_done" -eq 0 ] && [ "$t" -ge $((SOAK_SECONDS / 2)) ]; then
        screenshot soak-mid; mid_done=1
      fi

      if [ "$t" -ge "$next_nav" ] && [ "$t" -lt "$SOAK_SECONDS" ]; then
        next_nav=$((next_nav + NAV_EVERY))
        nav_cycle "$nav_total"
        screenshot "soak-nav-$nav_total"
      fi

      sleep "$TICK"
    done
    elapsed=$((SECONDS - t0))
  else
    stop_reason="streams did not connect within 90 s"
  fi
else
  stop_reason="app did not start"
fi
elapsed="${elapsed:-0}"

screenshot soak-end
save_logcat soak
read -r errors_total fatal_total <<< "$(error_counts)"

{
  echo "Scenario 20 soak summary"
  echo "duration: ${elapsed}s (target ${SOAK_SECONDS}s), sample interval ${SOAK_INTERVAL}s"
  awk -F, '
  NR > 1 {
    n++
    if ($5 ~ /^[0-9]+$/) { pss_n++; if (pss_n == 1 || $5 + 0 < pmin) pmin = $5 + 0; if ($5 + 0 > pmax) pmax = $5 + 0; plast = $5 + 0 }
    if ($6 ~ /^[0-9.]+$/) { cpu_n++; if ($6 + 0 > cmax) cmax = $6 + 0; clast = $6 }
    if (n == 1 || $2 + 0 < lmin) lmin = $2 + 0
    llast = $2
  }
  END {
    if (!n) { print "samples: 0"; exit }
    print "samples: " n
    if (pss_n) printf "PSS kB: min %d, max %d, last %d\n", pmin, pmax, plast; else print "PSS kB: n/a"
    if (cpu_n) printf "CPU %%: peak %.1f, last %s\n", cmax, clast; else print "CPU %: n/a"
    printf "LIVE tiles: min %d, last %d\n", lmin, llast
  }' "$CSV"
  echo "CONNECTING events (rises in CONNECTING tile count): $conn_events"
  echo "decoder/FATAL errors since start (cumulative): $errors_total total, $fatal_total FATAL/ANR"
  echo "navigation cycles passed: $nav_pass/$nav_total"
  echo "stop reason: ${stop_reason:-none}"
} > "$SUMMARY"
cat "$SUMMARY"

check "soak ran for the full duration without an early stop" test -z "$stop_reason"
check "all navigation cycles passed ($nav_pass/$nav_total)" test "$nav_pass" -eq "$nav_total"
check "no FATAL EXCEPTION, ANR, or decoder errors during soak" check_no_crash soak

tv shell am force-stop $PKG
check "app process gone after force-stop (no streams left running)" pid_gone
summary
