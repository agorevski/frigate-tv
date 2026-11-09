# FrigateTV — Architecture and Discovery Report

Host placeholders: `<frigate-host>` is the LAN address of the Frigate server (configured by the user in-app; not committed).

## 1. Discovery report

### Verified on 2026-11-08 against the live server

Repeatable check: `tests/03_frigate_api.sh`. Earlier discovery run (2026-11-08): **8 passed, 0 failed**, including a `reolink_camera_sub` 404 check, which is no longer in the script. Re-run after the docs refresh (2026-11-08, 22:18 local): **6 passed, 0 failed**. Its checks are the version, config, events, `front` snapshot and RTSP `DESCRIBE` for `front_sub` and `back_sub`.

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

## 3. Application architecture (current)

Kotlin, Jetpack Compose with Material 3, Media3 (ExoPlayer, RTSP and HLS), OkHttp, kotlinx.serialization, Coil, DataStore, Coroutines/Flow. Min SDK 26, target SDK 35, Java 17. There is no separate domain module; models live in `data/`.

### Layers

| Layer | Package | Responsibility |
| --- | --- | --- |
| Application | `FrigateTvApp`, `MainActivity` | Process-wide Coil loader (`frigateImageLoader`); activity keeps the screen on. |
| App state | `AppViewModel` | Profiles and the active profile, connection state, retry with backoff, network callback, detection polling and a health check every 8 polls (about 32 s), profile save and connection test. |
| Data | `data/` | `FrigateApi` makes the HTTP calls on `Dispatchers.IO` and throws `FrigateException`. `FrigateDto` maps JSON to DTOs. `FrigateRepository` binds one profile for the screens, builds media URLs and reuses recording segments for 30 s. `FrigateHttp` is the base OkHttp client (5 s connect, 10 s read) and provides the Coil loader and Media3 data source factory. `SettingsStore` persists profiles, the active id and per-profile preferences in DataStore. `SecretBox` encrypts passwords with an Android Keystore key. |
| Auth | `auth/` | `ProfileClients` derives per-profile clients from the base client: `plain` (profile TLS settings, used for login) and `authed` (adds the in-memory `frigate_token` cookie, one re-login on 401). `SelfSignedTrust` pins one profile's certificate. `FrigateSessions` keeps the client for each profile, keyed by profile id, and is the call factory for Coil and Media3. |
| Player | `player/` | `StreamPlayer` wraps ExoPlayer. `PlayerPool` caps live decoders at six (process-wide, `LocalPlayerPool`). `StreamBudget` allocates the cap between the featured camera and the rail. `ReconnectBackoff` (2 s to 30 s) and `FocusSettle` (0.8 s) hold the timing logic. |
| UI | `ui/` | Compose screens and shared components (`Focus`, `Theme`, `Components`, `RemoteImage`, `StateViews`, `SettingsScreen`). `ui/FrigateTvApp.kt` is the shell with the tabs. |
| Screen ViewModels | `ui/live/`, `ui/events/`, `ui/recordings/` | `LiveViewModel` holds focus and selection. Screens create `FrigateRepository(profile)` with `remember(profile)`. `EventsViewModel` and `RecordingsViewModel` take that repository and use it while the profile matches. |

Calls go downward: screens use ViewModels and `FrigateRepository`; the repository uses `FrigateApi`; `FrigateApi` gets its client from `auth/`. `auth/` builds on `FrigateHttp.client`, and `FrigateHttp` in turn exposes the `FrigateSessions.callFactory` for Coil and Media3, so `data/` and `auth/` refer to each other. The `player/` pure logic (backoff, pool, focus settle) has no Android or network dependency and is unit-tested directly.

### Request flow

```
Screen (Compose) -> ViewModel (LiveViewModel | EventsViewModel | RecordingsViewModel)
  -> FrigateRepository(profile) -> FrigateApi
  -> FrigateSessions.clientFor(profile)                 authed OkHttp client
  -> Frigate HTTP API (token cookie; 401 -> one re-login -> one retry)

Coil (RemoteImage) -> FrigateSessions.callFactory
  -> client of the registered profile whose server matches the URL, else FrigateHttp.client

Media3 HLS VOD and clip MP4 -> frigateMediaDataSourceFactory -> same callFactory

Live tiles and fullscreen -> Media3 RTSP (rtsp://host:8554/<stream>), not via OkHttp; no credentials
```

`FrigateSessions.callFactory` is the link for Coil and Media3, which do not know the profile. It matches the request URL's scheme, host and port against the registered profiles, so image and video requests to a Frigate server carry that server's login token and pinned certificate. A URL with no registered server uses the shared client. A profile's client is rebuilt when its connection settings change, which drops its token. A rename keeps it.

### Connection and recovery

- Profile save and test run through a throwaway `ProfileClients`, so a failed test never changes the active session. A profile with login logs in before the test. A self-signed profile that is not pinned yet records its fingerprint during the test.
- Each stream rebuilds after a failure with `ReconnectBackoff`. The app-level connection retries with its own backoff (5 s to 60 s).
- The live grid, Events and Recordings screens all reach Frigate through the active profile's repository, so they share the profile's login and pin.

Open risks and unverified items: see `PLAN.md` and `README.md` "Not yet done". Device verification of the refactor and of login and pinning has not been run.

## 4. Known limitations (discovery-time)
- Discovery was run from the development Mac. Device-side (TV, phone) reachability and codec support are not yet verified.
- Frigate auth behavior under enforcement is unverified.
- No ADB devices were connected during discovery.
