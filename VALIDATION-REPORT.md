# Validation report — NYISAY Virtual Racing Wheel V0.3

## Changes included

- Fixed the undeclared `mainExecutor` reference in `HidDeviceService.kt` by supplying an Android main-thread `Executor` directly to the documented `BluetoothHidDevice.registerApp(...)` signature.
- Registration state now waits for `onAppStatusChanged`; a successful return from `registerApp` is treated as acceptance of the request, not proof that registration completed.
- Added defensive checks around Bluetooth adapter availability/state, Android 12+ Bluetooth permissions, HID profile acquisition, safe proxy casting, registration, report sending and proxy cleanup.
- Added a generation guard to ignore stale HID profile callbacks after the service is stopped.
- Replaced the unsafe Binder cast in `MainActivity.kt` with a safe cast and added a diagnostic log.
- Updated the GitHub Actions workflow to install Android SDK platform 34/build tools 34.0.0, run `clean assembleDebug`, verify the expected APK exists and upload it as an artifact.
- Added the official SHA-256 checksum for the Gradle 8.2 binary distribution to `gradle-wrapper.properties`.

## Checks completed in this environment

- ZIP integrity check: passed.
- XML parsing: passed for project XML files.
- POSIX Gradle wrapper syntax (`sh -n`): passed.
- Static assertions for the HID Executor call, missing identifier, safe Binder cast, workflow APK path and checksum: passed.
- Official Gradle wrapper JAR checksum: matched the published checksum for Gradle 8.2.

## Not confirmed

A real Gradle build was attempted with `./gradlew clean assembleDebug --no-daemon --stacktrace`, but the runtime could not resolve `services.gradle.org` and exited with `java.net.UnknownHostException`. Android SDK/compiler availability could therefore not be exercised in this environment. The GitHub Actions workflow has not been run from this session. No APK is claimed to have been generated, and Bluetooth HID still requires a real-device test because OEM support varies.
