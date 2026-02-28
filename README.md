# ChillPill

Android app that adds a short pause before opening apps that are not on your whitelist. When you open a non-whitelisted app, a full-screen block screen appears with a configurable countdown; after it finishes you can either continue into the app or go back to the home screen.

## Features

- **App detection** via Accessibility Service: detects when you open any app.
- **Whitelist**: only whitelisted apps open without the pause screen.
- **Configurable wait time** (seconds) in Settings.
- **Manage whitelist**: full list of installed apps with checkboxes, Select all / Select none, and search.

## Setup

1. Build and install the app (Android Studio or `./gradlew installDebug`).
2. Open ChillPill and grant:
   - **Accessibility service** (Settings → Accessibility → ChillPill → On).
   - **Display over other apps** (optional; not required for the block screen).
   - **Notifications** (Android 13+; optional).
3. Open **Settings** in the app to set the wait time and choose which apps are on the whitelist.

## Build

```bash
./gradlew assembleDebug   # debug APK in app/build/outputs/apk/debug/
./gradlew installDebug    # build and install on connected device
```

## Debugging (logcat)

All app logs use the tag prefix `ChillPill/`. To see only ChillPill logs:

```bash
adb logcat -s ChillPill/A11y:V ChillPill/Block:V ChillPill/Main:V ChillPill/Settings:V
```

Or filter by substring: `adb logcat | findstr ChillPill` (Windows) / `adb logcat | grep ChillPill` (Unix).

- **ChillPill/A11y**: accessibility events (package/class), whitelist check, BlockActivity launch.
- **ChillPill/Block**: BlockActivity lifecycle, timer, Continue/Go home.
- **ChillPill/Main**: permission state (a11y, overlay).
- **ChillPill/Settings**: whitelist and timer load/save.

## Requirements

- Android 8.0 (API 26) or higher.
- Kotlin, Gradle 8.x, Android Gradle Plugin 8.3.x.
