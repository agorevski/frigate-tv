# FrigateTV — Plan and Progress

Goal (from `/goal`): the product works in its entirety on the TV, fully tested on the TV. Install nothing on the user's computer without asking first.

Requirements source: `instructions.md`.

## Setup (done)
- [x] JDK 17, Android SDK (platform 35, build-tools 35.0.0, platform-tools), Gradle 9.8.2 installed on this Mac with approval.
- [x] Google TV Streamer (Android 14) paired and connected over wireless ADB.
- [x] Connection details live in the untracked `.env` (template: `.env.example`). See README "Testing on the TV".
- [x] Device tests live in `tests/`, run with `tests/run_all.sh`.

## Status by phase
- [x] Phase 1 — Frigate discovery (see `ARCHITECTURE.md` §1).
- [x] Phase 2 — Foundation: Compose app, Frigate API client, DataStore persistence, server profiles (add/edit/delete/activate, test connection, unreachable profile refused and current profile kept).
- [x] Phase 3 — Live dashboard and RTSP playback: 4–5 sub-streams on the TV, balanced grid, offline tile with snapshot and retry.
- [x] Phase 4 — TV remote navigation and fullscreen (D-pad focus, OK opens fullscreen, LEFT/RIGHT switch, BACK returns, focus restored).
- [~] Phase 5 — Audio: selected camera uses the main stream with sound; mute toggle. Not verified by measurement.
- [~] Phase 6 — Events: list, filters, thumbnails, clip playback verified on the TV.
- [~] Phase 7 — Recordings: day/camera chips, timeline with event markers, HLS playback verified. Load takes several seconds on a busy day.
- [~] Phase 8 — Phone responsive UI: code written, not tested (no phone connected).
- [~] Phase 9 — Performance and reliability: decoder-error fix (release before create) verified; memory sample ~160 MB PSS. Not yet measured under a long run.
- [ ] Phase 10 — Full QA on device, screenshots, `TESTING.md`, final docs.

## Verified on the TV (2026-11-08)
- Layout: Automatic and 2-column both work; setting persists across restart.
- Profiles: failed activation keeps current profile; failed test shows reason; delete works.
- Settings: favorites and camera ordering controls work.
- Restart: last camera, layout, and active profile persist.

## Not yet verified on the TV
- Network interruption and automatic recovery (Wi-Fi drop). Code path exists; not exercised.
- Frigate unreachable for a whole session, beyond the single-profile test.
- Phone UI (portrait/landscape, touch).
- Extended multi-stream run and CPU/memory measurement over time.
- Audio actually audible (only the SOUND ON state is verified).
- Known open issue: some `tests/04_live_smoke.sh` focus checks still fail; being fixed.

## Known limitations
- Camera OSD clocks differ from wall time (Front ~10 min behind, Mailbox ~1 h off). Camera setting, not an app bug.
- `reolink_camera` has no working stream on this server; shows offline by design.
- Cleartext traffic is allowed app-wide for the LAN; should be tightened before release.
- Recordings day load is slow for busy cameras.

## Risks
- Five simultaneous RTSP streams on the Google TV Streamer; mitigated by sub-streams in grid, one main stream for selection, and a stream cap (6).
- Frigate auth is enabled on the server, yet most read endpoints answered without credentials. The app handles 401 as a connection failure; full auth flow not implemented.
- Frigate server address and TV address must stay out of committed files.

## Progress log
- 2026-11-08: Toolchain installed; TV paired; Milestone 1 (4 live streams on TV).
- 2026-11-08: Grid, fullscreen, navigation, Events, Recordings, Settings, profile flows verified on TV; decoder-error fix; focus-restore fixes.
- 2026-11-08: Device test suite added under `tests/`; `.env` introduced for connection details.
