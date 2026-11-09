# FrigateTV

A native Android client for a self-hosted [Frigate](https://frigate.video) NVR on the local network. It targets the Google TV Streamer (D-pad remote) and Android phones.

## Status

This is an early build. The table reflects what has been run on hardware, not what is planned.

The "Verified" rows were recorded before the repository refactor (ViewModels, `FrigateRepository`, `player/`) and the Frigate login and certificate pinning changes were merged. The merged code has not been run on a TV or phone. Those changes are covered by JVM unit tests and Robolectric UI tests only.

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
| Long-running multi-stream test | Not done (`tests/09_soak.sh` exists; no recorded 30-minute run) |
| Refactor (repository, per-screen ViewModels, `player/`) | Unit and Robolectric tests only; not run on device since merge |
| Frigate login (token, one re-login on 401) | Unit tests only (`auth/FrigateAuthTest`); not run on device or against an auth-enforcing server |
| Self-signed certificate pinning (per profile) | No automated test; not run on device |
| Password encryption (Android Keystore) | Not unit-tested (needs Android Keystore); not run on device |

Known server-side issues:
- `reolink_camera` has no working RTSP producer on the reference Frigate server (RTSP 404). Its tile correctly shows OFFLINE.
- Some camera burned-in clocks are wrong (Front about 10 minutes behind; Mailbox about an hour off). Recordings and events are correct; the on-screen overlay is not.

## Requirements

- macOS or Linux (tested on Ubuntu 24.04)
- JDK 17. The build targets Java 17. If your default `java` is another version, set `JAVA_HOME` for each Gradle command, for example `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew ...`. `tests/lib.sh` uses `JAVA_HOME` if set, else finds a JDK 17 (`java` on PATH, `/usr/lib/jvm/java-17-openjdk-amd64`, or Homebrew `openjdk@17`).
- Android SDK command-line tools with platform 35, build-tools 35.0.0, and platform-tools. Set `ANDROID_HOME` to the SDK root (for example `~/Android/Sdk`). If unset, the scripts try `~/Android/Sdk` and then the Homebrew location.
- `adb` on `PATH`, or at `$ANDROID_HOME/platform-tools/adb`. The test scripts add that directory to `PATH` when `adb` is missing.
- Gradle wrapper (included); system Gradle is only needed to regenerate the wrapper
- A Frigate server (tested with 0.16.2) reachable on the LAN
- For device testing: an Android TV / Google TV device with Wireless debugging enabled
- For the test scripts: `bash`, `curl`, `nc` (netcat), `python3`, and Pillow (`PIL`) for `07_events_recordings.sh`

## Configuration

Machine-specific values live in a local `.env` file at the repo root. It is gitignored.

```sh
cp .env.example .env
# edit .env
```

| Key | Meaning |
| --- | --- |
| `FRIGATE_HOST` | Frigate server address. Baked into the build as the default "Home" profile (if unset, the build uses 192.0.2.1). The Home profile uses the app's default ports, 9150 and 8554; change ports in the app's profile form. |
| `FRIGATE_PORT` | Frigate HTTP port used by the test scripts (`.env.example` sets 9150, the app default). |
| `FRIGATE_RTSP_PORT` | go2rtc RTSP port used by the test scripts (`.env.example` sets 8554, the app default). |
| `TV_ADB_SERIAL` | `host:port` of the TV's ADB connect endpoint. |
| `PHONE_ADB_SERIAL` | Optional phone serial. No script reads it; to run the device scripts against the phone, use `ADB_SERIAL="$PHONE_ADB_SERIAL"`. Leave empty if unused. |

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

The debug APK is written to `app/build/outputs/apk/debug/`. Release builds (R8 minify and resource shrinking) are configured but are not part of the documented checks, and no release pipeline exists.

## Code quality

Detekt (with ktlint formatting rules) and Android Lint run against baselines (`config/detekt/baseline.xml`, `app/lint-baseline.xml`). Detekt is set to `maxIssues: 0`, so new findings fail the build. Requires JDK 17. There is no CI workflow; run these checks locally.

```sh
./gradlew detekt                 # static analysis + ktlint style (config/detekt/detekt.yml, .editorconfig)
./gradlew detekt --auto-correct  # auto-fix ktlint style issues (use with care; touches source)
./gradlew :app:lintDebug         # Android Lint
./gradlew :app:assembleDebug :app:testDebugUnitTest detekt :app:lintDebug   # the full check set
```

Regenerate a baseline only after deliberately accepting new findings: `./gradlew :app:detektBaseline`, or delete `app/lint-baseline.xml` and rerun `:app:lintDebug`.

## Tests

Checks that need no device or server run on the JVM. Unit and UI tests (104 at the time of writing) run with:

```sh
./gradlew :app:testDebugUnitTest
```

- `app/src/test/.../contract/`: `FrigateApi` against Frigate 0.16.2 response fixtures in `app/src/test/resources/api/` (served by MockWebServer). Covers request paths and query strings, status and error handling, config and event parsing, and media URLs.
- `app/src/test/.../data/`: URL building, HTTP behavior, config parsing, models, and the settings store format.
- `app/src/test/.../auth/`: per-profile login, the `frigate_token` cookie, a single re-login on 401, and the wrong-password message. Certificate pinning is not covered by a test.
- `app/src/test/.../logic/`: `AppViewModel` profile lifecycle, failed connection tests, server outage and network-loss recovery (Robolectric, real DataStore), and the Events label filter.
- `app/src/test/.../player/`: reconnect backoff, the decoder cap in `PlayerPool`, and focus settle.
- `app/src/test/.../ui/`: Robolectric Compose UI tests for the app shell, Live, Events, Recordings and Settings screens, run against a local MockWebServer.

`TESTING.md` describes each test layer and the manual acceptance checklist.

Device and live-server checks are re-runnable scripts in `tests/`. They read `.env` and write output and logs to `tests/out/<timestamp>/` (gitignored). `ADB_SERIAL`, if set in the environment, overrides `TV_ADB_SERIAL` for the device scripts, for example to run them against a phone.

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
  FrigateTvApp.kt        Application; supplies the app-wide Coil ImageLoader
  MainActivity.kt        activity; keeps screen on; foreground/background hooks
  AppViewModel.kt        profiles, active profile, connection state, retry and health checks,
                         profile save and connection test
  auth/                  ProfileClients (per-profile HTTP clients: login, token, 401 re-login,
                         certificate pin) and FrigateSessions (client registry; call factory for Coil and Media3)
  data/                  FrigateApi (HTTP calls), FrigateDto (kotlinx.serialization), FrigateRepository
                         (per-profile entry point for screens), FrigateHttp (base OkHttp client, Coil
                         loader, Media3 data source), Models, SettingsStore (DataStore), SecretBox
                         (Keystore AES-GCM for passwords)
  player/                StreamPlayer (ExoPlayer), PlayerPool and StreamBudget (decoder cap),
                         ReconnectBackoff, FocusSettle, StreamState
  ui/                    FrigateTvApp (Compose shell), SettingsScreen, Focus, Theme, Components,
                         RemoteImage, StateViews, SplashScreen
  ui/live/               LiveScreen, LiveGrid, LiveTile, Fullscreen; LiveViewModel
  ui/events/             EventsScreen, EventCard, EventDetail, EventFilters, EventModels; EventsViewModel
  ui/recordings/         RecordingsScreen, Timeline, Playback, DayModel; RecordingsViewModel
app/src/main/res/xml/    network_security_config.xml
app/src/test/            JVM unit tests (data, contract, logic, player, auth) and Robolectric UI tests (ui)
config/detekt/           detekt configuration and baseline
app/lint-baseline.xml    Android Lint baseline
tests/                   device and live-server scripts
.github/dependabot.yml   Dependabot updates (Gradle and GitHub Actions); no workflows
LICENSE                  MIT license
ARCHITECTURE.md          layers, request flow, discovery report and playback decisions
TESTING.md               test layers, device script reference, acceptance checklist
CONTRIBUTING.md          prerequisites, checks, conventions, pull requests
PLAN.md                  milestones and progress log
```

Frigate endpoints in use (`data/FrigateApi.kt`). Verified on 0.16.2 (see `ARCHITECTURE.md` §1): `/api/version`, `/api/config`, `/api/events` (with `limit`, `after`, `camera`), `/api/{camera}/recordings`, `/api/{camera}/latest.jpg`, `/api/events/{id}/thumbnail.jpg`, `/api/events/{id}/snapshot.jpg`, `/api/events/{id}/clip.mp4`, and the HLS VOD playlist `/vod/{camera}/start/{s}/end/{e}/index.m3u8`. Not verified on 0.16.2: `in_progress=1` on `/api/events`, and `POST /api/login` (JSON `user` and `password`, returns the `frigate_token` cookie). `/api/login` is used only by profiles with Login set. Live video is RTSP from go2rtc on port 8554 (`rtsp://<host>:8554/<stream>`). Stream names come from the detect and record roles in `/api/config`. The app sends no RTSP credentials.


## Design notes

- Grid tiles use each camera's detect (sub) stream. The selected camera switches to its record (main) stream after 0.8 s of focus, which gives it audio.
- At most six live streams play at once; further tiles show a paused placeholder.
- A stream that errors is rebuilt with exponential backoff (2 s to 30 s). The old player is released before a new decoder is created; the TV's hardware decoder pool fails otherwise.
- Server outages: the app retries the connection with backoff (5 s, 10 s, 20 s, 40 s, then 60 s). Connectivity changes trigger an immediate retry. The app also runs a server health check about every 32 s.
- Profiles are validated with a connection test before activation. A failed test keeps the current profile.
- Cleartext HTTP is allowed app-wide (`network_security_config.xml` and `usesCleartextTraffic`) because Android cannot scope it to LAN addresses, and the default profile is plain HTTP on the LAN. Login tokens sent to an HTTP profile cross the LAN unencrypted; use HTTPS for remote access. HTTPS trust is not weakened globally: only system CAs are trusted, and self-signed certificates are accepted per profile (see below).
- Frigate login (authenticated port): a profile with "Login" set posts its username and password as JSON to `/api/login` and keeps the `frigate_token` cookie in memory only. A 401 triggers one re-login and one retry; a second 401 is returned as an error. Test connection logs in first, so a bad password reports "Invalid username or password". The login path is unit-tested against MockWebServer; it has not been run against a live server with auth enforced. RTSP (go2rtc) is not authenticated by the app; if go2rtc requires RTSP credentials, live video will not play.
- Security: profile passwords are stored only encrypted (AES-256-GCM, non-exportable Android Keystore key, `data/SecretBox.kt`) and are never logged. The token is never persisted. `allowBackup` is off. HTTP logging is not enabled.
- Self-signed HTTPS: "Trust self-signed" pins the server certificate's SHA-256 on the first test or save (trust on first use). The test shows the fingerprint; compare it with the server's. The pin is checked only by that profile's client (`auth/ProfileClients.kt`), and hostname verification is off for such profiles because the pin is the identity check. Switching trust off and on again re-pins. No automated test covers the pin check.
- Networking: one OkHttp client (`data/FrigateHttp.kt`, 5 s connect, 10 s read) is the base for the API, Coil image loads and Media3 HLS/MP4 playback. Each profile derives its own client from it (`auth/ProfileClients.kt`) for its login token and pinned certificate, so connections are pooled. Coil and Media3 get the authenticated client for the server a URL points to (`auth/FrigateSessions.kt`). Live RTSP does not go through OkHttp and carries no login. JSON is decoded with kotlinx.serialization (`data/FrigateDto.kt`). Live snapshots are never cached; event thumbnails are cached in memory and on disk. Recording segments per day are reused for 30 s.

## Not yet done

- Device verification of the refactor and of Frigate login and certificate pinning (the merged code has not been run on a TV or phone)
- Login and pinning against a live Frigate server with auth enforced
- RTSP credentials (go2rtc authentication is not supported)
- Phone UI verification (portrait, landscape, touch)
- Network-loss recovery on device (covered by unit tests only)
- Audio verification (audible output)
- Full acceptance run from `instructions.md` §18
- Recorded 30-minute soak (`tests/09_soak.sh`) and CPU/memory measurement over time
- CI and release pipeline (no workflows; Dependabot only)
- Out of scope for now: accessibility, picture-in-picture, PTZ, notifications, crash log, two-way audio

## License

MIT. See [LICENSE](LICENSE).

## Further reading

- [ARCHITECTURE.md](ARCHITECTURE.md): layers, request flow, discovery report, playback decisions
- [TESTING.md](TESTING.md): test layers, device script reference, manual acceptance checklist
- [CONTRIBUTING.md](CONTRIBUTING.md): prerequisites, checks, conventions, pull requests
- [PLAN.md](PLAN.md): phase status and progress log
