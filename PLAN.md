# FrigateTV — Plan and Progress

Goal (from `/goal`): the product works in its entirety on the TV, fully tested on the TV. Install nothing on the user's computer without asking first.

Source of requirements: `instructions.md`.

## Status
- [x] Phase 1 — Frigate discovery (see `ARCHITECTURE.md` §1)
- [ ] Toolchain (JDK 17+, Android SDK, Gradle) — **blocked on user approval to install**
- [ ] Device connection (TV and phone via wireless ADB) — **blocked on user pairing**
- [ ] Phase 2 — Android foundation, server profiles
- [ ] Phase 3 — Live dashboard and RTSP playback (prototype with 5 streams on the TV first)
- [ ] Phase 4 — TV remote navigation and fullscreen
- [ ] Phase 5 — Audio (selected camera only)
- [ ] Phase 6 — Event browser and playback
- [ ] Phase 7 — Historical recordings timeline
- [ ] Phase 8 — Phone responsive UI
- [ ] Phase 9 — Performance and reliability
- [ ] Phase 10 — Full QA on device, screenshots, docs (`TESTING.md`)

## Blockers (need user action)
1. **Install toolchain on this Mac.** Current state: `/usr/bin/java` is a stub (no runtime), no Android SDK, no Gradle. Requested install: a JDK 17 (e.g. Temurin via Homebrew), Android SDK command-line tools, platform 35, build-tools, and platform-tools. Awaiting approval.
2. **Connect the devices.** `adb devices -l` returned no devices. Wireless debugging must be paired on the TV (Google TV Streamer) and phone, then `adb connect`.

## Acceptance (from instructions.md §18)
Track each scenario in `TESTING.md` with actual results and device model. Simulated results must be labeled as such.

## Risks
- Five simultaneous RTSP streams on the Google TV Streamer. Mitigate with sub-streams in grid mode, one main stream for selected/fullscreen, and a concurrency cap.
- Frigate auth is enabled on the server. Discovery read endpoints without credentials. The app must handle a 401 gracefully.
- `reolink_camera` returned 404 on go2rtc during discovery. It must show as offline with backoff, not crash.
- Frigate server address must not be committed.

## Progress log
- 2026-11-08: JDK 17, Android SDK (platform 35, build-tools 35.0.0, platform-tools), and Gradle 9.8.2 installed on this Mac with approval.
- 2026-11-08: Google TV Streamer (`Google_TV_Streamer`, Android 14) paired and connected over wireless ADB.
- 2026-11-08: Milestone 1 (prototype) built: `./gradlew :app:assembleDebug` succeeds. Installed on the TV. Showed 4 of 5 sub-stream RTSP feeds live at once (front, back, driveway, mailbox). `reolink_camera_sub` returns RTSP DESCRIBE 404 and shows as a blank tile. Logcat: only that one stream errored.
