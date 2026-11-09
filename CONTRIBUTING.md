# Contributing

## Prerequisites

- macOS or Linux (tested on Ubuntu 24.04)
- JDK 17. Set `JAVA_HOME` (for example `/usr/lib/jvm/java-17-openjdk-amd64`), or let `tests/lib.sh` find one. Prefix Gradle commands with it if your default `java` is another version.
- Android SDK with platform 35, build-tools 35.0.0, and platform-tools. Set `ANDROID_HOME`.
- A Frigate server on the LAN, and optionally a Google TV or Android device with Wireless debugging for device tests.

See the README for the full requirements.

## Configuration

Machine-specific values live in `.env` at the repo root. It is gitignored. Copy `.env.example` and follow the README "Configuration" section.

## Build

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug
```

## Checks

Run these before opening a PR. There is no CI workflow, so run them locally:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug :app:testDebugUnitTest detekt :app:lintDebug
bash -n tests/*.sh        # syntax check for the device scripts
```

Device tests need a connected TV and a reachable Frigate server:

```sh
tests/run_all.sh          # all scripts
tests/run_all.sh 04 05    # a subset, by script prefix
```

See [TESTING.md](TESTING.md) for what each layer covers.

## Conventions

- Kotlin: follow the official Kotlin coding conventions.
- Compose: keep composables small and stateless where practical. Hoist state to the ViewModel or the screen.
- TV input: every new screen or control must work with the D-pad alone. Check that focus is visible and returns correctly after BACK.
- Never commit `.env`, server addresses, ADB pairing codes, keystores, or other secrets. Use `.env.example` for placeholders.
- Comment only where the code needs clarification.
- By contributing, you agree that your changes are released under the [MIT license](LICENSE).

## Pull requests

- Keep PRs small and focused on one change.
- Describe what you changed and why.
- State what was verified and how. Separate "verified on the TV" (or phone) from "built and unit-tested only". Do not claim hardware results that did not happen.
- Update README, PLAN.md, or TESTING.md when behavior, setup, or test results change.
