# Build FrigateTV — Native Android Frigate Client

You are an expert Android engineer, Android TV UX designer, video-streaming engineer, and QA automation engineer.

Build a polished native Android application called **FrigateTV** that connects to self-hosted Frigate NVR installations over a local network.

The application must work exceptionally well on:
1. Google TV Streamer (4K), controlled using a standard directional-pad TV remote.
2. Android phones, using a responsive touchscreen interface.

This is a real application implementation task, not a prototype or mockup. Build, deploy, test, and refine the application.

## 1. Initial Frigate server

Default server:

- Host: `<your-frigate-host>` (set in local `.env`, see README)
- Port: `9150`
- URL: `http://<your-frigate-host>:9150/`
- Authentication: None.

Do not hardcode this server address throughout the application. It must be configurable through persistent local settings.

The initial installation has five cameras:
- Four RK cameras.
- One Reolink doorbell camera.

Discover the actual camera names, IDs, capabilities, resolutions, and stream configurations from Frigate. Never assume camera IDs based on their physical descriptions.

## 2. Mandatory discovery phase

Before implementing the video layer, inspect the running Frigate installation.

Determine:

- Frigate version.
- Available REST API endpoints.
- Camera discovery and metadata.
- Camera online/offline status.
- Available stream configurations.
- Supported streaming protocols.
- WebRTC, MSE, go2rtc, or other available playback mechanisms.
- Available audio streams and codecs.
- Recording availability.
- Event and detection APIs.
- Snapshot endpoints.
- Available recording playback methods.

Test actual endpoints against the configured server.

Create a technical discovery report documenting findings and recommended implementation architecture.

Do not assume that a particular endpoint, streaming protocol, or codec is available. Verify it.

If the server is temporarily unreachable, implement against documented Frigate interfaces using a mock/test data provider, but clearly distinguish verified capabilities from assumptions.

## 3. Application architecture

Build a native Android application.

Preferred technology:
- Kotlin.
- Jetpack Compose.
- Material 3.
- AndroidX Media3 / ExoPlayer where appropriate.
- Android DataStore for persistent settings.
- Coroutines and Flow.
- A maintainable MVVM architecture.

Evaluate the most suitable video playback approach based on actual Frigate capabilities and Google TV Streamer compatibility.

Avoid embedding the entire application in a WebView unless native playback proves impractical.

Use hardware-accelerated video decoding wherever possible.

The application must support:
- Android phones.
- Android TV / Google TV.
- 4K output.
- D-pad navigation.
- Touch navigation.

Configure Android TV launcher compatibility and appropriate application manifests.

## 4. Main live camera dashboard

The live camera dashboard is the application's primary screen.

On startup:
- Connect to the last active Frigate profile.
- Discover all cameras.
- Show all five currently configured cameras immediately.
- Restore the last-used layout and camera preferences where applicable.
- Begin live playback automatically.

Design a polished dark security-monitoring interface.

Requirements:

- Dynamic camera grid based on camera count.
- Optimize grid arrangement for available screen dimensions.
- Support 1, 2, 3, 4, 5, 6, 9, and more cameras.
- All cameras should be accessible without requiring manual configuration.
- Camera names should be readable from across a room.
- Display camera connectivity status.
- Clearly distinguish selected cameras.
- Show timestamps where appropriate.
- Indicate active detection events.
- Avoid unnecessary visual clutter.

For five cameras, display all five simultaneously using a balanced layout rather than leaving an unnecessarily large empty area.

Allow users to:
- Select cameras.
- Enter fullscreen.
- Switch cameras.
- Change layouts.
- Reorder cameras where practical.
- Mark favorite cameras.

Persist layout preferences locally, preferably per server profile.

## 5. Video quality and playback

Implement adaptive playback behavior.

Grid mode:
- Prefer lower-resolution streams when available.
- Minimize CPU/GPU utilization.
- Avoid unnecessary decoding overhead.
- Maintain smooth playback.

Selected camera:
- Increase quality when practical.

Fullscreen:
- Use the highest appropriate available resolution and frame rate.
- Preserve aspect ratio.
- Avoid unnecessary transcoding.

Camera switching should be fast and reliable.

Implement:
- Buffering indicators.
- Stream failure handling.
- Reconnection.
- Graceful offline camera presentation.
- Appropriate retry/backoff behavior.
- Resource management when switching screens.

Never open more streams than the device can reasonably decode.

Test actual simultaneous playback on the Google TV Streamer.

If hardware decoding limitations prevent all cameras from playing smoothly, degrade gracefully rather than crashing or freezing.

## 6. Camera audio

Audio should be enabled automatically for the selected camera.

Requirements:
- Audio for the selected camera.
- No simultaneous audio from multiple cameras.
- Switching selection should switch the audio source.
- Provide an obvious mute/unmute control.
- Avoid audio feedback or duplicate playback.
- Handle cameras without available audio gracefully.
- Preserve sensible audio preferences.

Verify audio availability and codec compatibility for each camera.

## 7. Fullscreen camera interface

Pressing OK on a selected camera opens fullscreen.

Fullscreen should:
- Maximize usable video area.
- Display camera name.
- Display connection status.
- Support audio control.
- Support switching to previous/next camera.
- Provide playback controls where supported.
- Display an unobtrusive overlay.

TV remote behavior:
- D-pad navigation.
- OK toggles controls or selects an action.
- Left/right switch cameras when controls are hidden.
- Back returns to the camera grid.

Controls should disappear after inactivity where appropriate.

## 8. Event browser

Implement a dedicated Events screen.

Discover events through Frigate's actual event API.

Features:
- Chronological event list.
- Thumbnail previews.
- Event timestamps.
- Camera names.
- Object labels.
- Person filtering.
- Vehicle filtering.
- Other object filtering.
- Camera filtering.
- Date filtering.
- Event detail screen.
- Event playback.

Make browsing comfortable using a TV remote.

Use large thumbnails and readable text.

Handle missing thumbnails and unavailable recordings gracefully.

## 9. Historical recordings and timeline

Implement a historical recordings interface.

Features:
- Browse available recordings.
- Select a camera.
- Select a date.
- Navigate a timeline.
- Seek backward and forward.
- Play and pause.
- Display timestamps.
- Jump to relevant event timestamps where possible.

Use Frigate's verified recording/playback mechanisms.

Do not invent recording URLs.

The experience should be usable with a standard TV remote.

## 10. Multiple Frigate server profiles

Implement Settings → Server Profiles.

Support multiple saved servers, with exactly one active profile at a time.

Each profile contains:
- User-defined profile name.
- Hostname or IP address.
- Port.
- HTTP or HTTPS scheme.
- Optional additional connection settings if required.

Default profile:
- Name: Home.
- Address: `<your-frigate-host>`.
- Port: 9150.
- Scheme: HTTP.

Features:
- Add profile.
- Edit profile.
- Delete profile.
- Rename profile.
- Test connection.
- Activate profile.
- Display active profile.
- Remember the last active profile.

Switching profiles must:
- Disconnect from the previous server.
- Release its playback resources.
- Connect to the new server.
- Discover its cameras.
- Restore that profile's layout and preferences.

Persist profiles locally using Android DataStore or an appropriate persistent repository.

Profiles must survive application restarts and device reboots.

Validate a connection before activating a newly configured profile. Preserve the existing working configuration if a new connection fails.

No cloud service or external account should be necessary.

## 11. Persistent application settings

Persist:
- Saved Frigate profiles.
- Active server profile.
- Camera grid layout.
- Camera ordering.
- Favorite cameras.
- Last selected camera.
- Audio preferences.
- Playback preferences.
- Other relevant user preferences.

Settings must survive application restarts and device reboots.

## 12. Network reliability

The application is designed for local network usage.

Do not introduce:
- Cloud dependencies.
- Reverse proxy requirements.
- External relay services.
- Mandatory authentication services.
- Remote application backends.

Implement:
- Network connectivity monitoring.
- Automatic reconnection.
- Server health checks.
- Camera health checks.
- Appropriate retry intervals.
- Recovery after Wi-Fi interruptions.
- Clear connection state.

When network connectivity returns, reconnect without requiring the user to restart the app.

Use appropriate Android network security configuration for configurable local HTTP/HTTPS servers. Do not globally weaken TLS validation for HTTPS.

## 13. Android TV remote navigation

This is a critical requirement.

The Google TV Streamer must be usable entirely with a standard TV remote.

Support:
- D-pad up.
- D-pad down.
- D-pad left.
- D-pad right.
- OK / Enter.
- Back.
- Home behavior.

Every interactive element must have sensible focus behavior.

Implement:
- Clear focus indicators.
- Predictable navigation order.
- No keyboard or mouse requirement.
- No inaccessible controls.
- No focus traps.
- Appropriate focus restoration after navigation.

The application should feel like a commercial Android TV application.

## 14. Responsive mobile interface

The same application must work on Android phones.

Support:
- Portrait orientation.
- Landscape orientation.
- Touch-friendly camera grid.
- Fullscreen camera viewing.
- Event browsing.
- Historical playback.
- Server profile management.
- Settings.

Use responsive layouts rather than simply scaling up the TV interface.

On phones, prioritize efficient screen usage and touch interaction.

## 15. Visual design

Product name: FrigateTV.

Design direction:
- Premium security-monitoring product.
- Dark interface.
- Clean typography.
- High contrast.
- Minimal visual clutter.
- Smooth but restrained transitions.
- Large camera previews.
- Excellent readability at television viewing distances.

Avoid:
- Generic developer dashboards.
- Excessive menus.
- Tiny text.
- Excessive animations.
- Smartphone-style navigation patterns on TV.

The application should look polished and production-ready.

## 16. Performance and resource management

Optimize for Google TV Streamer hardware.

Measure:
- CPU utilization.
- Memory utilization.
- Video decoding performance.
- Startup time.
- Camera switching time.
- Stream buffering.
- Playback stability.
- Concurrent stream capacity.

Ensure:
- Playback resources are released when no longer needed.
- Streams do not continue unnecessarily in the background.
- Audio sessions are managed correctly.
- The UI remains responsive during playback.
- Failed streams do not cause memory leaks.
- Network retries do not create duplicate player instances.

Use hardware decoding and appropriate stream selection.

Do not assume 4K decoding is practical for five simultaneous streams.

## 17. Deployment and development workflow

Build a debug APK and deploy it to the connected Android device using ADB.

Identify connected devices and distinguish between the Google TV Streamer and Android phone.

Use ADB to:
- Install the APK.
- Launch the application.
- Capture screenshots.
- Inspect UI hierarchy.
- Collect logcat output.
- Diagnose crashes.
- Test navigation.
- Verify persistence.
- Test network recovery.

Keep the build reproducible.

Provide:
- Complete source code.
- README.md.
- Build instructions.
- ADB installation instructions.
- APK artifact.
- Technical architecture documentation.
- Known limitations.

## 18. Testing and acceptance criteria

Do not consider the application finished simply because it compiles.

Test these scenarios:

1. First launch with the default Home profile.
2. Discover all five cameras.
3. Display all five live feeds.
4. Navigate every camera using only the TV remote.
5. Enter and exit fullscreen.
6. Switch selected cameras.
7. Verify selected-camera audio.
8. Browse events.
9. Filter events by person and vehicle.
10. Play an available event.
11. Browse historical recordings.
12. Change camera grid layout.
13. Restart the application and verify preferences persist.
14. Create a second server profile.
15. Switch between profiles.
16. Change the IP address and port.
17. Test an unreachable server.
18. Disconnect and restore Wi-Fi.
19. Verify automatic reconnection.
20. Run extended simultaneous playback testing.

Capture screenshots of the major screens and document test results.

Fix all reproducible critical and major bugs before declaring completion.

Where physical hardware is unavailable, clearly document which tests were simulated rather than verified.

## 19. Implementation priorities

Prioritize development in this order:

Phase 1: Frigate discovery and technical architecture.

Phase 2: Native application foundation and server profiles.

Phase 3: Live camera dashboard and video playback.

Phase 4: TV remote navigation and fullscreen viewing.

Phase 5: Audio.

Phase 6: Event browser and playback.

Phase 7: Historical recording timeline.

Phase 8: Mobile responsive interface.

Phase 9: Performance optimization and reliability.

Phase 10: Full QA, ADB deployment, screenshots, and documentation.

Build incrementally. Keep the application runnable throughout development.

Do not stop after writing an architecture document or generating boilerplate.

**Your ultimate goal:** Deliver a working, polished FrigateTV APK that turns a Google TV Streamer and an Android phone into excellent local-network Frigate monitoring clients.



# Haiku 5.5 Agent Instructions

## Development approach

You are implementing FrigateTV using Claude Haiku 5.5. Work autonomously, but favor small, verifiable increments over large speculative implementations.

1. Inspect the repository, project structure, existing code, and available development environment before making changes.
2. Create or update `PLAN.md` with milestones, dependencies, risks, and acceptance criteria.
3. Implement one independently buildable milestone at a time.
4. Keep the application compiling and runnable throughout development.
5. After every meaningful code change, run the most relevant real build, test, or static-analysis command.
6. Do not report a feature as complete unless you have actually exercised it or clearly state why testing was impossible.
7. Do not start another milestone while the current milestone has unresolved build failures.
8. Prefer small, targeted changes over broad rewrites. Avoid unnecessary abstractions and dependencies.
9. Do not invent Frigate endpoints, camera IDs, streaming capabilities, or device capabilities. Verify them against available documentation and the actual server.
10. If blocked, explain the specific blocker, what you tried, and the smallest action needed to proceed. Do not silently substitute a different implementation.

## Prioritize technical risk

Prove the most technically uncertain parts early:

1. Connect to the actual Frigate server.
2. Discover all five cameras and their actual identifiers.
3. Verify the available live-stream protocols and codecs.
4. Build a minimal native video playback prototype.
5. Test five simultaneous live streams on the Google TV Streamer.
6. Test remote navigation and video performance.
7. Only then invest heavily in the finished dashboard design.

Do not spend significant time polishing a UI around a playback architecture that has not been proven to work.

## GitHub workflow

Treat the GitHub repository as the source of truth.

- Use a feature branch for substantial features.
- Commit each working milestone with a concise, descriptive commit message.
- Do not commit broken or unbuildable intermediate work.
- Review `git diff` before committing.
- Keep `main` buildable.
- Add a `.gitignore` appropriate for Android Studio, Gradle, Kotlin, local configuration, build artifacts, and IDE files.
- Never commit credentials, tokens, pairing codes, private keys, or machine-specific configuration.
- Do not put the owner's private Frigate server address into public source code. Use an untracked local configuration or a first-run setup flow; provide safe example values in documentation.

## Persistent project documentation

Maintain these files as the project evolves:

- `AGENTS.md` — durable instructions for future coding-agent sessions.
- `PLAN.md` — milestone checklist and current progress.
- `ARCHITECTURE.md` — application architecture and major technical decisions.
- `DECISIONS.md` — important decisions, alternatives considered, and rationale.
- `TESTING.md` — test procedures, actual results, device models, and known limitations.

Update documentation when implementation decisions change. Do not claim hardware testing that did not happen.

## Wireless ADB development

Use ADB over Wi-Fi as the normal deployment and testing workflow.

Support the developer connecting multiple Android devices, including an Android phone and Google TV Streamer.

Before issuing device commands:

- Run `adb devices -l`.
- Confirm the intended target is connected and in the `device` state.
- Use `adb -s DEVICE_SERIAL` whenever more than one device is connected.
- Never assume that the phone and TV have identical capabilities or configurations.

The development workflow should support:

- Wireless pairing and reconnection.
- Installing and upgrading the debug APK.
- Launching and force-stopping the application.
- Capturing screenshots.
- Inspecting UI hierarchy where supported.
- Collecting and filtering Logcat.
- Simulating app restarts and network interruptions.
- Testing D-pad navigation on the TV.
- Testing touch interactions and orientation on the phone.

Do not require USB debugging or a permanent USB connection. Document how to pair devices using Android Wireless Debugging, including the distinction between the pairing port and connection port.

If a device is unavailable, continue with buildable work and automated tests. Clearly distinguish emulator/simulated results from tests performed on actual hardware.

## Completion and reporting

At the end of each milestone, report:

- What was implemented.
- Which files changed.
- The build and tests actually run.
- Which tests passed or failed.
- Which hardware devices were tested.
- Remaining blockers and the next milestone.

Keep working until the requested milestone is implemented and checked. Do not stop merely because a plan or partial implementation exists. Do not add unrelated features or refactors without approval.


# One more GitHub recommendation

I'd structure the repository like this:
frigate-tv/
├── AGENTS.md
├── README.md
├── PLAN.md
├── ARCHITECTURE.md
├── DECISIONS.md
├── TESTING.md
├── .gitignore
├── app/
│   └── src/
├── gradle/
├── build.gradle.kts
└── settings.gradle.kts