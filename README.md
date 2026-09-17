# Chillpill

Limit screen time by enforcing a wait period and grace period before using selected apps.

## Overview

Chillpill helps you reduce usage of chosen apps by:

- Requiring a short wait before opening a restricted app
- Allowing a time-limited "grace period" per session after the wait
- Showing a block screen when you try to open a restricted app (or when the grace period has expired)

See [ARCHITECTURE.md](ARCHITECTURE.md) for architecture, data model, and flows, and [AGENTS.md](AGENTS.md) for how to work in this repo (humans and AI agents).

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
- `.agents/skills/` — task-specific skills for AI agents
- `docs/plans/` — historical implementation plans

## License

See [LICENSE](LICENSE).
