# FrigateTV — Architecture and Discovery Report

Host placeholders: `<frigate-host>` is the LAN address of the Frigate server (configured by the user in-app; not committed).

## 1. Discovery report

### Verified on 2026-11-08 against the live server

Repeatable check: `tests/03_frigate_api.sh` (run 2026-11-08): **8 passed, 0 failed**. Its `reolink_camera_sub` 404 check is a known-offline expectation, not a pass on a working stream.

Server: Frigate `0.16.2-4d58206` (`/api/version`); config reports `0.16-0`. Reachable over HTTP on the API port from the LAN. go2rtc RTSP (8554) reachable. go2rtc API (1984) **not reachable** (connection refused / 000).

**Auth:** config has `auth.enabled: true`, and `GET /api/auth` returns **401**. However, every read endpoint below returned **200 without credentials**. So read access is open on this server. Behavior under credentialed or enforced access from the device is not tested.

| Endpoint | Status observed | Notes |
| --- | --- | --- |
| `GET /api/version` | 200 `text/plain` | `0.16.2-4d58206` |
| `GET /api/config` | 200 `application/json` | ~31 KB; camera definitions and go2rtc streams |
| `GET /api/stats` | 200 `application/json` | |
| `GET /api/auth` | 401 | auth-gated |
| `GET /api/events?limit=N` | 200 `application/json` | fields include `camera`, `label`, `start_time`, `end_time`, `has_clip`, `has_snapshot` |
| `GET /api/<camera>/latest.jpg` | 200 `image/jpeg` | checked for all 5 cameras |
| `GET /api/<camera>/recordings/summary?timezone=UTC` | 200 `application/json` | `reolink_camera` returned an empty body |
| `GET /api/<camera>/recordings?after=&before=` | 200 `application/json` | segment list (`start_time`, `end_time`, `motion`, `objects`), checked on `driveway` |
| `GET /api/events/<id>/thumbnail.jpg` | 200 `image/jpeg` | |
| `GET /api/events/<id>/snapshot.jpg` | 200 `image/jpeg` | |
| `GET /api/events/<id>/clip.mp4` | 200 `video/mp4` | ~6.2 MB for a 11 s event on `driveway` |
| `GET /vod/<camera>/start/<s>/end/<e>/index.m3u8` | 200 `application/vnd.apple.mpegurl` | VOD playlist, `#EXT-X-PLAYLIST-TYPE:VOD`, `seg-N-v1.ts` segments, checked on `driveway` |
| `GET /api/go2rtc/streams` | 200 `application/json` | per-stream producers and consumers |
| `GET /api/go2rtc/api/frame.mp4?src=front` | 200 `video/mp4; codecs="avc1.4D0029"` | one-frame MP4 |

**Cameras** (from `/api/config`; IDs are Frigate camera names, all `enabled: true`, each with `record` and `detect` ffmpeg inputs):
`front`, `back`, `mailbox`, `driveway`, `reolink_camera`.

**Stream codecs** (RTSP `DESCRIBE` on port 8554, re-checked this run):

| Stream | RTSP result | Codecs in SDP |
| --- | --- | --- |
| `front` | 200 | H264, PCMA/8000 audio |
| `back` | 200 | H264, MPEG4-GENERIC/8000 audio |
| `mailbox` | 200 | H264, MPEG4-GENERIC/8000 audio |
| `driveway` | 200 | H264, MPEG4-GENERIC/8000 audio |
| `reolink_camera` | **404** | none. go2rtc lists 2 producers for it but DESCRIBE still fails. Cause not investigated. |
| `front_sub`, `back_sub`, `mailbox_sub`, `driveway_sub` | 200 | H264, no audio |
| `reolink_camera_sub` | **404** | none (go2rtc lists 1 producer) |

### Documented or assumed, not verified

- **`/api/cameras` returns 404** and **`/api/profile`** returned 200 in an earlier pass. Not re-tested this run.
- **`/live/<stream>/index.m3u8`** is the go2rtc web UI, not HLS (earlier pass). Not re-tested.
- **go2rtc WebRTC/MSE** signaling (`/live/webrtc/api/ws`, `/live/mse/api/ws`) answered 400 without a WebSocket upgrade in an earlier pass. Not re-tested. No WebSocket client was run.
- **Recording and retention settings** (`record.enabled`, mode `all`, 7-day recording retention, 90-day event retention, Frigate audio detection disabled) come from an earlier config read. Not re-checked this run.
- **Physical camera mapping** of each ID is not verified.
- **Main-stream resolutions** are not read from the SDP. Confirm on the device.
- **Audio decode** (PCMA, AAC) on the TV and phone is not tested.
- **Auth enforcement** for credentialed or protected access from the device is not tested.
- **Device-side reachability and simultaneous decode** on the Google TV Streamer and phone are not tested.
- **go2rtc port 1984** is unreachable from this machine. Not verified whether it is reachable from the device.

## 2. Playback decision

**Verified options:**
1. **RTSP via go2rtc on port 8554** (verified). Use Media3 ExoPlayer's RTSP module (`media3-exoplayer-rtsp`). Only option that gives direct, low-latency live H264 with audio over the LAN, and the only one confirmed to work from the app's network stack.
2. HLS VOD for recordings: verified via `/vod/<camera>/start/<s>/end/<e>/index.m3u8` (200). Not usable for live. The `/live/.../index.m3u8` path is the go2rtc web UI.
3. go2rtc MSE/WebRTC: requires a WebSocket signaling flow against go2rtc's internal API. Not chosen. Adds a protocol the app would have to implement.

**Chosen approach (provisional, to be validated on device):** ExoPlayer with RTSP for live tiles and fullscreen. Sub-streams in grid mode, main streams for fullscreen and selected camera. Hardware decoding via MediaCodec (default in Media3).

Open risks:
- Concurrency on the TV: five simultaneous RTSP streams, with H264 decode and audio on the selected camera only. Must measure on the Google TV Streamer.
- `reolink_camera` has no working stream right now. The app must show it as offline with retry/backoff.
- The go2rtc RTSP port 8554 is on the same host as the Frigate API. The app must derive the RTSP URL from the profile host and the fixed port, and expose the port as a setting.

## 3. Application architecture (planned)

- Kotlin, Jetpack Compose, Material 3, Media3, DataStore, Coroutines/Flow.
- MVVM. `data` (Frigate API client, RTSP URL builder, DataStore repositories), `domain` (profile, camera, layout models), `ui` (compose screens: dashboard, fullscreen, events, recordings, profiles, settings).
- Single-activity app with Leanback-style launcher entry (`LEANBACK_LAUNCHER`) and phone launcher entry.
- Per-profile settings: camera order, favorites, layout, last camera, audio preference.
- Server profile stored in DataStore. Activation validates `GET /api/version` and `GET /api/config` before saving.
- Network security config: cleartext allowed only for the configured host range (`http` is the default profile scheme). HTTPS validation must not be disabled globally.

## 4. Known limitations (current)
- Discovery was run from the development Mac. Device-side (TV, phone) reachability and codec support are not yet verified.
- Frigate auth behavior under enforcement is unverified.
- No ADB devices were connected during discovery.
