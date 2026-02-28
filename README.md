# ChillPill

Android app that adds a short pause before opening apps that are not on your whitelist. When you open a non-whitelisted app, a full-screen overlay appears with a configurable countdown; after it finishes you can either continue into the app or go back to the home screen.

## Features

- **App detection** via Accessibility Service: detects when you open any app.
- **Whitelist**: only whitelisted apps open without the pause screen.
- **Configurable wait time** (seconds) in Settings.
- **Manage whitelist**: full list of installed apps with checkboxes, Select all / Select none, and search.

## Setup

1. Build and install the app (Android Studio or `./gradlew installDebug`).
2. Open ChillPill and grant:
   - **Accessibility service** (Settings → Accessibility → ChillPill → On).
   - **Display over other apps** (overlay permission).
   - **Notifications** (Android 13+) so the app can show the overlay.
3. Open **Settings** in the app to set the wait time and choose which apps are on the whitelist.

## Build

```bash
./gradlew assembleDebug   # debug APK in app/build/outputs/apk/debug/
./gradlew installDebug    # build and install on connected device
```

## Requirements

- Android 8.0 (API 26) or higher.
- Kotlin, Gradle 8.x, Android Gradle Plugin 8.3.x.
