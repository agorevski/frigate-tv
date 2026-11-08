# FrigateTV — Architecture and Discovery Report

Host placeholders: `<frigate-host>` is the LAN address of the Frigate server (configured by the user in-app; not committed).

## 1. Discovery report (verified 2026-11-08)

Verified by live requests against the configured Frigate server. Capabilities marked **assumed** were not tested.

### Server
| Item | Result |
| --- | --- |
| Frigate version | `0.16.2-4d58206` (`GET /api/version`), config reports `0.16-0` |
| Web/API port | `9150` over HTTP, reachable from LAN |
| Auth | Config reports `auth.enabled: true`. `GET /api/auth` returns **401**. Most read endpoints below answered **without credentials** during discovery. **Behavior under auth enforcement is unverified** — needs a check from the device. |
| go2rtc RTSP | Port **8554** reachable on the LAN (raw RTSP `DESCRIBE` returns 200) |
| go2rtc API port 1984 | Not reachable from the LAN (closed) |

### Endpoints verified (HTTP status)
| Endpoint | Status | Notes |
| --- | --- | --- |
| `GET /api/version` | 200 | plain text version string |
| `GET /api/config` | 200 | full config, includes camera definitions and go2rtc streams |
| `GET /api/stats` | 200 | system and camera stats |
| `GET /api/events?limit=N` | 200 | event list. Fields: `id`, `camera`, `label`, `sub_label`, `zones`, `start_time`, `end_time`, `has_clip`, `has_snapshot`, `top_score`, `data`, `thumbnail` |
| `GET /api/events/summary` | 200 | per-camera / label summary |
| `GET /api/go2rtc/streams` | 200 | per-stream producers and SDP |
| `GET /api/<camera>/latest.jpg` | 200 | JPEG snapshot |
| `GET /api/<camera>/recordings/summary?timezone=UTC` | 200 | hourly summary: `events`, `motion`, `objects`, `duration` |
| `GET /api/profile` | 200 | |
| `GET /api/cameras` | **404** | Not an endpoint in this version. Camera list comes from `/api/config`. |
| `GET /api/recordings/summary` | not reachable (000) | not used |
| `GET /api/auth` | **401** | auth-gated |
| `GET /api/go2rtc/api/frame.mp4?src=<stream>` | 200 `video/mp4` | snapshot-style MP4 frame |
| `GET /live/webrtc/api/ws?src=<stream>` | 400 without WS upgrade | go2rtc WebRTC signaling; browser/WebSocket only |
| `GET /live/mse/api/ws?src=<stream>` | 400 without WS upgrade | go2rtc MSE signaling; WebSocket only |
| `GET /live/<stream>/index.m3u8` | 200 `text/html` | **Not an HLS playlist** (the go2rtc web UI). Do not use. |

### Cameras (from `/api/config`, names are Frigate camera IDs)
| Camera ID | Record role | Detect role | Detect size (config) |
| --- | --- | --- | --- |
| `front` | `front` (main) | `front_sub` | 352×240 |
| `back` | `back` | `back_sub` | 704×480 |
| `mailbox` | `mailbox` | `mailbox_sub` | 704×480 |
| `driveway` | `driveway` | `driveway_sub` | 704×480 |
| `reolink_camera` | `reolink_camera` | `reolink_camera_sub` | 1280×720 |

Which physical camera each ID maps to is **not** verified. The app must use these IDs from discovery, not guesses.

### Streams and codecs (RTSP `DESCRIBE` over port 8554, verified)
| Stream | Result | Codecs in SDP |
| --- | --- | --- |
| `front` | 200 | H264, PCMA (audio) |
| `back` | 200 | H264, MPEG4-GENERIC (AAC audio) |
| `driveway` | 200 | H264, MPEG4-GENERIC |
| `mailbox` | 200 | H264, MPEG4-GENERIC |
| `reolink_camera` | **404** | no producer. Likely offline or misconfigured. Re-check at runtime. |
| `front_sub`, `back_sub`, `driveway_sub`, `mailbox_sub` | 200 | H264 (no audio) |
| `reolink_camera_sub` | **404** | no producer |

Audio: `front` (PCMA/G.711A), `back`, `driveway`, `mailbox` (AAC, MPEG4-GENERIC) have audio tracks. Frigate's own audio detection is disabled for all cameras. Playback audio is only from go2rtc.

Resolutions for the main streams were not read from the SDP. Confirm on the device.

### Recording and events
- Recording is enabled (`record.enabled: true`), mode `all`, retained 7 days. Detection events retained 90 days.
- Recording playback endpoints (for example `/vod/<camera>/start/<t>/end/<t>`) were **not tested** in this pass. Test them before building the timeline.

## 2. Playback decision

**Verified options:**
1. **RTSP via go2rtc on port 8554** (verified). Use Media3 ExoPlayer's RTSP module (`media3-exoplayer-rtsp`). Only option that gives direct, low-latency live H264 with audio over the LAN, and the only one confirmed to work from the app's network stack.
2. HLS: not available. The `/live/.../index.m3u8` path is the web UI.
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
