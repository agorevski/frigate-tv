# Testing

This document describes the test layers, what each device script checks, and the manual acceptance checklist. Results from actual runs are recorded in `PLAN.md` and in the output folders under `tests/out/`. Nothing here is a result on its own.

## Test layers

| Layer | Location | Runs on | Status |
| --- | --- | --- | --- |
| JVM unit tests | `app/src/test/` | Host JVM | Available: `data/` (parsing, URLs, settings format), `contract/` (Frigate API fixtures), `logic/` (profiles, recovery, event filter), `player/` (backoff, pool cap, focus settle). Run with `./gradlew :app:testDebugUnitTest`. |
| Robolectric Compose UI tests | `app/src/test/java/com/frigatetv/app/ui/` | Host JVM | Available. Run with `./gradlew :app:testDebugUnitTest`; they use a local MockWebServer for the Frigate API. |
| Static checks (detekt, lint) | Gradle | Host JVM | Available: `./gradlew detekt :app:lintDebug` |
| Device and API scripts | `tests/*.sh` | Host, driving a TV over ADB | Available |
| Manual acceptance | This document | Physical TV or phone | Partly done; see the checklist |

Host checks:

```sh
./gradlew detekt :app:lintDebug :app:testDebugUnitTest
```

Checks that were shell scripts and now run as unit tests (no device or server needed):

| Former script check | Unit test |
| --- | --- |
| 03: endpoint paths, query strings, status handling, config lists cameras and stream roles, events parsing, snapshot and media URLs | `contract/FrigateApiContractTest.kt` (MockWebServer, fixtures in `app/src/test/resources/api/`) |
| 06: test of an unreachable profile fails; activation refused, Home kept; nothing stored | `logic/AppViewModelProfileTest.kt` |
| 07: People filter keeps only person events | `logic/EventLabelFilterTest.kt` |
| 08: add and activate, switch back, refused port, delete, relaunch keeps the profile list | `logic/AppViewModelProfileTest.kt` |
| 08 (logic only): server outage to Failed then automatic reconnect; network lost to "Network unavailable" then reconnect | `logic/AppViewModelProfileTest.kt` |

## Device and API scripts

### Prerequisites

- A populated `.env` at the repo root (see the README "Configuration" section). Scripts exit with status 2 if it is missing.
- `JAVA_HOME` set to JDK 17, or a JDK 17 found by `tests/lib.sh`.
- `ANDROID_HOME` pointing at an SDK with platform 35. `adb` on `PATH`, or under `$ANDROID_HOME/platform-tools`.
- On the host: `bash`, `curl`, `nc` (netcat), `python3`, and Pillow (`PIL`) for `07_events_recordings.sh`.
- For scripts 01, 02, and 04 to 09: a TV reachable at `TV_ADB_SERIAL` over wireless ADB, with Wireless debugging enabled and the device paired once.
- For scripts 01, 03, and 04 to 09: the Frigate server at `FRIGATE_HOST` reachable, with five cameras and go2rtc streams `front_sub` and `back_sub`.
- Some scripts depend on the reference server's content. Camera names (Front, Back, Mailbox, Driveway, Reolink Camera), filter labels, and event and recording data are assumed. A different server needs script changes.

### Running

```sh
tests/run_all.sh          # all scripts, in order
tests/run_all.sh 04 05    # scripts whose name starts with 04 or 05
bash tests/04_live_smoke.sh   # one script
```

`run_all.sh` writes every script's log to a single folder, `tests/out/<timestamp>/`, as `NN_name.log`. Run alone, a script creates its own `tests/out/<timestamp>/` folder. Scripts exit non-zero on any failed check and print one `PASS` or `FAIL` line per check. Read those lines and the logs. `tests/out/` is gitignored.

### Outputs

| File | Written by | Content |
| --- | --- | --- |
| `NN_name.log` | `run_all.sh` | Console output of each script |
| `build.txt` | 02 | Gradle output |
| `logcat-<stage>.txt` | 02, 04, 05, 06, 07, 08, 09 | Full logcat dump at the end of the stage |
| `errors-<stage>.txt` | `check_no_crash` | Matching FATAL, ANR, NO_MEMORY, and decoder errors |
| `frigate-config.json` | 03 | Server config as fetched |
| `*.png` | Most device scripts | Screenshots of the screens named in each script below |
| `events-ui.xml`, `events-list.png` | 07 | UI dump and screenshot used to check event cards |
| `playback-frames.txt` | 07 | Frame differences measured during playback |
| `soak.csv`, `soak-summary.txt` | 09 | Per-minute samples and the run summary |

### Script reference

**01_check_env.sh** checks the toolchain and connections. It verifies JDK 17 on `PATH`, the SDK platform 35 directory, the Gradle wrapper, that `.env` defines `FRIGATE_HOST`, that the TV answers over ADB, and that `GET /api/version` returns successfully on Frigate. It needs no app.

**02_build_install.sh** builds the debug APK (`./gradlew :app:assembleDebug`), installs it with `adb install -r`, and launches it. It checks that the APK exists, the install reports `Success`, the app process is running, and no crash or decoder error appears in logcat. It writes `build.txt` and the startup logcat.

**03_frigate_api.sh** runs without a device. It is a live-server smoke test: it checks that `/api/version`, `/api/config`, `/api/events?limit=1`, and `/api/front/latest.jpg` return successfully on the real server, and that go2rtc answers RTSP `DESCRIBE` with 200 for `front_sub` and `back_sub`. The response contract (shapes, parsing, query strings) is in `FrigateApiContractTest`.

**04_live_smoke.sh** tests the live grid and fullscreen. It checks that at least four tiles show LIVE, that no tile is still CONNECTING, that a tile has focus, and that D-pad RIGHT or LEFT moves focus. It presses OK to open fullscreen, checks the camera name shown, presses RIGHT and checks that the camera changed, presses BACK and checks that the grid returns with focus on a tile. It also checks that the app is alive and that logcat has no crash or decoder error.

**05_tabs.sh** opens each tab with the D-pad: Events (filters visible, text "Type"), Recordings ("Play from here"), Settings ("Server profiles"), then Live (at least one LIVE tile). It checks that the app is alive and that logcat has no crash or decoder error after all tabs.

**06_profile_failure.sh** adds a profile named "ZZ Unreachable Test" pointing at `10.255.255.1`, through the real form, IME and D-pad. It checks that Test connection reports "Cannot reach server" and that Save and activate shows "Keeping the current profile". The refusal logic (Home stays active, nothing stored, nothing stored after a restart) is in `AppViewModelProfileTest`. Buttons are found by label. If a button cannot be focused, the script cancels the form instead of saving.

**07_events_recordings.sh** covers the event browser and recordings.
- Events: the list shows cards, each with a camera name and a thumbnail. Thumbnails are judged by pixel spread (placeholders are flat colour). The Vehicles filter shows the empty-state message, because the reference server has no vehicle events in range. The People filter repopulates the list (which labels each filter keeps is in `EventLabelFilterTest`). Opening the first card shows the detail screen with a clip, and frames change between screenshots, so playback is advancing. BACK returns to the list with focus on the same card.
- Recordings: selects Driveway and Yesterday, waits for the timeline clock to read 12:00:00, presses "Play from here", and checks the text "Playing Driveway from 12:00:00". Frames must change during playback. BACK returns to the timeline with focus on it.

**08_profile_network.sh** covers profile lifecycle and network recovery on the device (instructions §10 to §12, scenarios 14 to 19). It runs in five steps. The profile state logic is in `AppViewModelProfileTest`.
1. Adds a profile "QA-Test-Server" using `FRIGATE_HOST` and the ports from `.env`, through the form. Checks that Test connection reports "Connected: Frigate", activates it, and checks that the live grid loads.
2. Switches back to Home and checks that the live grid loads.
3. Edits the test profile to port 9999. Checks that Test connection fails and that the save is refused with the form still open. Cancels the form.
4. Deletes the test profile from the list.
5. Network outage: schedules a job on the TV that runs `svc wifi disable`, waits 40 seconds, then runs `svc wifi enable`. The script checks that the ADB link drops, that ADB reconnects, and that the app returns to Connected with LIVE tiles within 90 seconds. It then re-enables Wi-Fi as a safety step. The script does not observe the app's Offline state, because the app does not log network transitions; the unit test covers that state.

A leftover "QA-Test-Server" profile from an earlier run is removed at the start. The script's header comment says Wi-Fi is switched off only over USB. The code does not check the transport. It schedules the outage on the TV whenever the TV is reachable, including over wireless ADB. The header comment is out of date.

**09_soak.sh** covers the extended simultaneous playback test (scenario 20). Defaults: 1800 seconds, sampled every 60 seconds. Override with `SOAK_SECONDS` and `SOAK_INTERVAL`. Each sample records LIVE and CONNECTING tile counts, process ID, PSS, CPU, and the cumulative count of errors. Every 300 seconds it runs a navigation cycle (RIGHT, OK, RIGHT, BACK) and checks that at least four tiles are LIVE afterwards. The run stops early if the process dies or its PID changes, if fewer than three tiles are LIVE for more than 60 seconds, or if a FATAL EXCEPTION or ANR appears. The app is force-stopped on exit. Because a full run takes 30 minutes by default, `run_all.sh` takes at least that long.

## Manual acceptance checklist

Source: instructions §18 (scenarios 1 to 20). Scripts cover part of several scenarios. Items marked **Manual** are not covered by any script. Mark each result as Pass, Fail, or Not run, with the device and build used. Note any simulated step.

| # | Scenario | Covered by | Notes |
| --- | --- | --- | --- |
| 1 | First launch with the default Home profile | 02 (launch only) | Manual check of first-run state |
| 2 | Discover all five cameras | 03 (server config only) | **Manual:** all five appear in the app |
| 3 | Display all five live feeds | 04 (at least four LIVE) | **Manual:** all five tiles; `reolink_camera` shows OFFLINE by design |
| 4 | Navigate every camera with the remote | 04 (partial) | **Manual:** every tile reached |
| 5 | Enter and exit fullscreen | 04 | |
| 6 | Switch selected camera | 04, 09 | |
| 7 | Verify selected-camera audio | None | **Manual.** Only the SOUND ON state has been observed. Audible output has not been measured. |
| 8 | Browse events | 07 | |
| 9 | Filter events by person and vehicle | 07 (Vehicles empty state, People repopulates); unit: `EventLabelFilterTest` | The reference server has no vehicle events in range, so the vehicle filter with results is not covered |
| 10 | Play an available event | 07 | |
| 11 | Browse historical recordings | 07 | |
| 12 | Change camera grid layout | None | **Manual** |
| 13 | Restart and verify preferences persist | Unit: profile list and active profile across relaunch. **Manual:** layout and selected camera | |
| 14 | Create a second server profile | 08 step 1; unit: `AppViewModelProfileTest` | |
| 15 | Switch between profiles | 08 steps 1 and 2; unit: switching back to Home | |
| 16 | Change IP address and port | 08 step 3 (port only); unit: refused edit is not stored | **Manual:** IP address change |
| 17 | Test an unreachable server | 06 (form); unit: `AppViewModelProfileTest` | |
| 18 | Disconnect and restore Wi-Fi | 08 step 5 | Real Wi-Fi outage on the TV, scheduled by the script with `svc wifi`. Unit: network loss and regain |
| 19 | Verify automatic reconnection | 08 step 5; unit: server outage then reconnect | The app's Offline state is not observed by the script; the unit test checks it |
| 20 | Extended simultaneous playback | 09 | Default 30 minutes; see the note on 09 above |

Additional items from the instructions that no script covers:
- Phone layout in portrait and landscape, and touch input (§14). **Manual.** No phone was connected for the tests recorded in `PLAN.md`.
- Screenshots of all major screens (§18). Scripts save some screens. **Manual** for the full set.
- Visual design review (§15). **Manual.**

## Known limitations

- Some focus checks in `04_live_smoke.sh` were still failing when `PLAN.md` was last updated. Check the current log before relying on the script.
- `reolink_camera` has no working RTSP producer on the reference server. Its tile is OFFLINE by design.
- Some camera on-screen clocks are wrong (Front about 10 minutes behind, Mailbox about an hour off). Recordings and events are correct. The on-screen overlay is not.
- Wi-Fi outage tests depend on the TV's shell tools (`svc`, `nohup`, `ps`) and on the ADB link reconnecting.
- Authentication under enforced Frigate auth has not been tested.
- Device tests are timing-based and depend on the reference server's data. Failures can reflect the environment rather than the app. Read the logs before changing the app.
