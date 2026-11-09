# FrigateTV

A native Android client for a self-hosted [Frigate](https://frigate.video) NVR on the local network. It targets the Google TV Streamer (D-pad remote) and Android phones.

## Status

This is an early build. The table reflects what has been run on hardware, not what is planned.

| Area | State |
| --- | --- |
| Live grid (5 cameras, balanced layout, LIVE/OFFLINE status) | Verified on Google TV Streamer |
| D-pad navigation, fullscreen, LEFT/RIGHT camera switch, BACK | Verified on Google TV Streamer |
| Column layout setting (Automatic / 2 / 3 columns) and persistence across restart | Verified on Google TV Streamer |
| Recordings timeline and HLS playback from a timeline point | Verified on Google TV Streamer |
| Events list, thumbnails, label/camera/range filters, clip playback | Verified on Google TV Streamer |
| Profile add, failed connection test, refused activation | Verified on Google TV Streamer (scripted) |
| Profile activation, deletion, favorites and camera order | Verified manually on Google TV Streamer |
| Network interruption and automatic recovery | Not tested on device |
| Audio (selected camera) | Sound state shown; audible output not measured |
| Phone (portrait/landscape, touch) | Not tested (no phone connected) |
| Long-running multi-stream test | Not done |

Known server-side issues:
- `reolink_camera` has no working RTSP producer on the reference Frigate server (RTSP 404). Its tile correctly shows OFFLINE.
- Some camera burned-in clocks are wrong (Front about 10 minutes behind; Mailbox about an hour off). Recordings and events are correct; the on-screen overlay is not.

## Requirements

- macOS or Linux (tested on Ubuntu 24.04)
- JDK 17. `tests/lib.sh` uses `JAVA_HOME` if set, else finds a JDK 17 (`java` on PATH, `/usr/lib/jvm/java-17-openjdk-amd64`, or Homebrew `openjdk@17`).
- Android SDK command-line tools with platform 35, build-tools 35.0.0, and platform-tools. Set `ANDROID_HOME` to the SDK root (for example `~/Android/Sdk`). If unset, the scripts try `~/Android/Sdk` and then the Homebrew location.
- `adb` on `PATH`, or at `$ANDROID_HOME/platform-tools/adb`. The test scripts add that directory to `PATH` when `adb` is missing.
- Gradle wrapper (included); system Gradle is only needed to regenerate the wrapper
- A Frigate server (tested with 0.16.2) reachable on the LAN
- For device testing: an Android TV / Google TV device with Wireless debugging enabled

## Configuration

Machine-specific values live in a local `.env` file at the repo root. It is gitignored.

```sh
cp .env.example .env
# edit .env
```

| Key | Meaning |
| --- | --- |
| `FRIGATE_HOST` | Frigate server address. Baked into the build as the default "Home" profile. |
| `FRIGATE_PORT` | Frigate HTTP port (default 9150). |
| `FRIGATE_RTSP_PORT` | go2rtc RTSP port (default 8554). |
| `TV_ADB_SERIAL` | `host:port` of the TV's ADB connect endpoint. |
| `PHONE_ADB_SERIAL` | Optional phone serial. Leave empty if unused. |

Never put ADB pairing codes in `.env`. Pair once interactively, then only the connect address is needed.

### Connecting to the TV over wireless ADB

1. On the TV: Settings → System → Developer options → Wireless debugging → On.
2. Pair once (interactive; the code is shown on the TV):
   `adb pair <ip>:<pairing-port> <code>`
3. Find the connect port on the Wireless debugging screen, or with `adb mdns services`.
4. Put `TV_ADB_SERIAL=<ip>:<connect-port>` in `.env`, then `adb connect <ip>:<connect-port>`.
5. Check with `adb devices -l`; the TV should show `device`.

The connect port changes when Wireless debugging restarts. Update `.env` when that happens.

## Build and install

```sh
./gradlew :app:assembleDebug
adb -s "$TV_ADB_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Code quality

Detekt (with ktlint formatting rules) and Android Lint run against baselines (`config/detekt/baseline.xml`, `app/lint-baseline.xml`). New findings fail the build. Requires JDK 17.

```sh
./gradlew detekt                 # static analysis + ktlint style (config/detekt/detekt.yml, .editorconfig)
./gradlew detekt --auto-correct  # auto-fix ktlint style issues (use with care; touches source)
./gradlew :app:lintDebug         # Android Lint
./gradlew :app:assembleDebug detekt :app:lintDebug   # everything
```

Regenerate a baseline only after deliberately accepting new findings: `./gradlew :app:detektBaseline`, or delete `app/lint-baseline.xml` and rerun `:app:lintDebug`.

## Tests

Checks that need no device or server run on the JVM, together with detekt and lint:

```sh
./gradlew detekt :app:lintDebug :app:testDebugUnitTest
```

- `app/src/test/.../contract/`: `FrigateApi` against Frigate 0.16.2 response fixtures in `app/src/test/resources/api/` (served by MockWebServer). Covers request paths and query strings, status and error handling, config and event parsing, and media URLs.
- `app/src/test/.../data/`: URL building, HTTP behavior, config parsing, models, and the settings store format.
- `app/src/test/.../auth/`: per-profile login and token handling.
- `app/src/test/.../logic/`: `AppViewModel` profile lifecycle, failed connection tests, server outage and network-loss recovery (Robolectric, real DataStore), and the Events label filter.
- `app/src/test/.../player/`: reconnect backoff, the decoder cap in `PlayerPool`, and focus settle.
- `app/src/test/.../ui/`: Robolectric Compose UI tests for the app shell, Live, Events, Recordings and Settings screens, run against a local MockWebServer.

`TESTING.md` describes each test layer and the manual acceptance checklist.

Device and live-server checks are re-runnable scripts in `tests/`. They read `.env` and write output and logs to `tests/out/<timestamp>/` (gitignored).

| Script | What it checks |
| --- | --- |
| `01_check_env.sh` | Toolchain, `.env`, TV reachable, Frigate reachable |
| `02_build_install.sh` | Builds the APK, installs it, launches, checks for crashes |
| `03_frigate_api.sh` | Frigate endpoints and go2rtc RTSP streams answer on the real server (no device needed) |
| `04_live_smoke.sh` | Live grid, focus, fullscreen, camera switch, BACK |
| `05_tabs.sh` | Each tab loads and the app stays alive |
| `06_profile_failure.sh` | Unreachable profile through the real form: refusal message is shown |
| `07_events_recordings.sh` | Event cards and thumbnails, Vehicles empty state, playback advancing, recordings |
| `08_profile_network.sh` | Profile form on device, live streams after switching, real Wi-Fi outage and reconnect |
| `09_soak.sh` | 30-minute simultaneous playback soak (memory, CPU, decoder errors) |

Run everything, or a subset by prefix:

```sh
tests/run_all.sh
tests/run_all.sh 04 05
```

Each script exits non-zero on failure. Read the `PASS`/`FAIL` lines and the logs in `tests/out/` rather than re-running ad-hoc commands.

## Project layout

```
app/src/main/java/com/frigatetv/app/
  MainActivity.kt        activity; keeps screen on; foreground/background hooks
  AppViewModel.kt        profiles, connection state, health checks, preferences
  data/                  models, Frigate HTTP client, DataStore persistence, Keystore secret wrapper;
                         FrigateRepository is the per-profile entry point for screens
  auth/                  per-profile login (token), self-signed certificate pinning
  player/                ExoPlayer lifecycle: decoder cap (PlayerPool), reconnect
                         backoff, focus settle; the pure logic has JVM tests
  ui/                    shared components (focus, images, states), theme, shell,
                         SettingsScreen
  ui/live/               Live grid, tiles, fullscreen; LiveViewModel (focus, selection)
  ui/events/             event list, filters, cards, detail; EventsViewModel
  ui/recordings/         day timeline, playback; RecordingsViewModel
app/src/test/            JVM unit tests, contract tests and Robolectric UI tests
tests/                   re-runnable device and API checks
ARCHITECTURE.md          discovery report and playback decisions
TESTING.md               test layers, device script reference, acceptance checklist
CONTRIBUTING.md          prerequisites, checks, conventions, pull requests
PLAN.md                  milestones and progress log
```

Frigate endpoints in use (verified on 0.16.2): `/api/version`, `/api/config`, `/api/login` (when a profile uses login), `/api/events` (including `in_progress`), `/api/{camera}/recordings`, `/api/{camera}/latest.jpg`, `/api/events/{id}/thumbnail.jpg`, `/api/events/{id}/clip.mp4`, and the HLS VOD playlist `/vod/{camera}/start/{s}/end/{e}/index.m3u8`. Live video is RTSP from go2rtc on port 8554.

## Design notes

- Grid tiles use each camera's detect (sub) stream. The selected camera switches to its record (main) stream after 0.8 s of focus, which gives it audio.
- At most six live streams play at once; further tiles show a paused placeholder.
- A stream that errors is rebuilt with exponential backoff (2 s to 30 s). The old player is released before a new decoder is created; the TV's hardware decoder pool fails otherwise.
- Connectivity changes trigger an immediate retry. The app also runs a server health check about every 32 s.
- Profiles are validated with a connection test before activation. A failed test keeps the current profile.
- Cleartext HTTP is allowed app-wide (`network_security_config.xml`) because Android cannot scope it to LAN addresses. Login tokens sent to an HTTP profile cross the LAN unencrypted; use HTTPS for remote access. Only system CAs are trusted globally.
- Frigate login (authenticated port, Frigate 0.14+): a profile with "Login" set posts its username and password to `/api/login` and keeps the `frigate_token` cookie in memory. A 401 triggers one re-login and one retry; a second 401 is an error. Test connection logs in first, so a bad password reports "Invalid username or password". RTSP (go2rtc) is not authenticated by the app; if go2rtc requires RTSP credentials, live video will not play.
- Security: the password is stored only encrypted (AES-256-GCM, Android Keystore key) and is never logged. The token is never persisted. `allowBackup` is off. HTTP logging is not enabled.
- Self-signed HTTPS: "Trust self-signed" pins the server certificate's SHA-256 on the first test or save (trust on first use). The test shows the fingerprint; compare it with the server's. The pin is enforced for that profile only. Switching trust off and on again re-pins.
- Networking: one OkHttp client (`data/FrigateHttp.kt`, 5 s connect, 10 s read) serves the API, Coil image loads and Media3 HLS/MP4 playback, so they share keep-alive connections. Each profile gets a derived client (`auth/ProfileClients.kt`) for its login token and pinned certificate. JSON is decoded with kotlinx.serialization (`data/FrigateDto.kt`). Live snapshots are never cached; event thumbnails are cached in memory and on disk.

## Not yet done

- Phone UI verification
- Network-loss recovery test on device
- Audio verification
- Full acceptance run from `instructions.md` §18
- Frigate login on a server with auth enforced: implemented (see Design notes), not yet verified on hardware.
