#!/usr/bin/env bash
# Checks the Frigate endpoints and go2rtc RTSP streams the app depends on (no device needed).
. "$(dirname "$0")/lib.sh"

B="http://$FRIGATE_HOST:$FRIGATE_PORT"
CONFIG="$OUT/frigate-config.json"
curl -sf -m 10 "$B/api/config" > "$CONFIG"
check "GET /api/version returns 200" curl -sf -m 8 "$B/api/version"
check "GET /api/config returns 200" test -s "$CONFIG"
check "config lists 5 cameras" sh -c "python3 -c 'import json,sys; c=json.load(open(sys.argv[1]))[\"cameras\"]; sys.exit(0 if len(c)==5 else 1)' '$CONFIG'"
check "GET /api/events returns 200" curl -sf -m 8 "$B/api/events?limit=1"
check "GET /api/front/latest.jpg returns 200" curl -sf -m 8 -o /dev/null "$B/api/front/latest.jpg"
rtsp_status() {
  printf "DESCRIBE rtsp://$FRIGATE_HOST:$FRIGATE_RTSP_PORT/$1 RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\n\r\n" \
    | nc -w 4 "$FRIGATE_HOST" "$FRIGATE_RTSP_PORT" | head -1 | tr -d '\r'
}
rtsp_ok() { rtsp_status "$1" | grep -q ' 200 '; }
check "RTSP front_sub DESCRIBE 200" rtsp_ok front_sub
check "RTSP back_sub DESCRIBE 200" rtsp_ok back_sub
summary
