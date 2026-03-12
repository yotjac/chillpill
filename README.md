# Chillpill

Limit screen time by enforcing a wait period and grace period before using selected apps.

## Overview

Chillpill helps you reduce usage of chosen apps by:

- Requiring a short wait before opening a restricted app
- Allowing a time-limited "grace period" per session after the wait
- Showing a block screen when you try to open a restricted app (or when the grace period has expired)

See the [design document](specs/chillpill-design.md) for architecture, data model, and flows.

## Requirements

- Android SDK 24+
- Android Studio or compatible IDE

## Build and run

**From the command line** (Gradle wrapper is included):

```bash
./gradlew assembleDebug
./gradlew installDebug
```

On Windows:

```bat
gradlew.bat assembleDebug
gradlew.bat installDebug
```

Or open the project in Android Studio (File → Open → select the project folder) and run the app from the IDE.

## Project structure

- `app/` — Application module (UI, data, navigation)
- `specs/` — Design and feature specs (spec-first workflow)

## License

See [LICENSE](LICENSE).
