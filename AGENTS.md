# AGENTS.md — how to work in this repo

Read this first. It is short on purpose; deeper material is linked, not inlined.

## What this is

Chillpill is a single-module Android app (Kotlin, Jetpack Compose, `:app`, package
`com.chillpill`) that reduces screen time: an AccessibilityService intercepts launches of
user-chosen "restricted" apps, shows a block screen with a wait timer, then grants a
time-limited grace period tracked by a foreground service. All data is local (DataStore +
Room). No network layer, no DI framework, no tests yet.

## Where context lives (load in this order, only as needed)

1. `AGENTS.md` (this file) — rules, commands, gotchas. Always loaded.
2. `ARCHITECTURE.md` — the full mental model: package map, data layer, service flows,
   navigation, concurrency. Read the relevant section before touching a subsystem; read
   all of it before any change to `service/`.
3. `.agents/skills/<name>/SKILL.md` — task-specific procedures. Load only the ones that
   match the task (see list below). Do not load skills that are irrelevant.
4. `specs/` — one spec per feature (Proposal / Design / Tasks). Shipped specs stay as
   decision records; read the matching spec before modifying a feature that has one.
5. `docs/plans/` — older implementation plans kept for history.

Skills available in `.agents/skills/`:

| Skill | Use when |
|-------|----------|
| `compose-ui` | Writing or refactoring Composables, state hoisting, theming |
| `android-coroutines`, `kotlin-concurrency-expert` | Anything in `service/`, flows, scopes, timers |
| `android-data-layer` | Room / DataStore changes (ignore its Retrofit/offline-sync parts) |
| `android-accessibility` | A11y semantics in Compose; not the AccessibilityService itself |
| `android-testing` | Adding the first tests |
| `android-architecture`, `android-gradle-logic` | Reference only — see "Conventions win" below |
| `frontend-design` | Visual demos for UI specs (HTML mockups), not app code |

## Workflow rules

- **Spec first.** Before implementing any new or modified feature, write
  `specs/<feature-name>.md` with `## Proposal`, `## Design`, `## Tasks` (checklist of
  `- [ ]` items). If the Design makes UI decisions, include a visual demo. Get the spec
  reviewed, then implement from `## Tasks`, checking items off. If a feature changes
  later, update its spec first. Do not write implementation code before the spec exists.
- **Check skills first.** Look at the table above and load the matching skill(s) before
  starting. Skip the rest.
- **Keep the map current.** If you add, remove, rename or move a file, or change a data
  flow, service interaction, DataStore key, Room schema or route, update the matching
  section of `ARCHITECTURE.md` in the same change. A stale map is worse than none.
- **Conventions win over generic skills.** Several skills describe a "modern" setup
  (Hilt, Clean Architecture, version catalogs, convention plugins, Retrofit). This project
  deliberately has none of that: manual DI in `ChillpillApp`, concrete repositories,
  ViewModels calling repositories directly, versions declared in `app/build.gradle.kts`.
  Do not introduce those frameworks unless a spec explicitly asks for it.

## Commands

Gradle wrapper is checked in. On Windows use `gradlew.bat` instead of `./gradlew`.

```bash
./gradlew assembleDebug          # build
./gradlew installDebug           # install on the connected device/emulator
./gradlew lintDebug              # Android lint
./gradlew testDebugUnitTest      # unit tests (none exist yet — add under app/src/test)
```

Release signing reads `keystore.properties` from the repo root (never committed). If it is
absent, release builds are unsigned; that is expected on agent machines.

The interesting behaviour (blocking, grace, re-intervention, suggestions) only runs on a
real device or emulator with the accessibility service and usage-access permission
enabled. It cannot be exercised from a unit test today, so for changes in `service/` or
the block/re-intervention activities, describe in the PR/spec exactly how you verified
them manually, or leave the verification step for the human.

## Gotchas (things that have bitten before)

- `ChillpillAccessibilityService` processes events on a single-threaded scope
  (`Dispatchers.Default.limitedParallelism(1)`). Keep it that way; ordering matters.
- Events from Chillpill's own package (block screen, re-intervention, overlay) are
  filtered before `processEvent` so they don't reset the same-app guard. Breaking this
  causes double block screens on splash → main transitions.
- Leaving a restricted app for a *different* app refreshes its full grace window. The
  grace service polls `BlockingSharedState.isInGracePeriod(pkg)` every second instead of
  running a fixed countdown precisely so this refresh is honoured. Don't replace the
  polling with a fixed delay.
- `GracePeriodService` keeps one job per package (`graceJobs`). Starting grace for app B
  must not cancel app A's job.
- `BlockingSharedState` (in-memory `object` in `service/`) is the live cross-service
  state. `data/blockstate/BlockSharedState.kt` (SharedPreferences) is instantiated but
  unused — don't build on it.
- Re-intervention is shown only from `GracePeriodService` on grace expiry while the user
  is still in the app. The accessibility service always launches the regular
  `AppBlockActivity`, never `ReInterventionActivity`.
- Room is configured with `allowMainThreadQueries()` and
  `fallbackToDestructiveMigration()`. Bump the DB version when the schema changes; there
  are no migrations.
- Settings are draft-only until Save; reducing restrictions goes through
  `SettingsConfirmationScreen` with a wait. Don't add a shortcut that bypasses it.
- `.cursor/` is gitignored, so anything placed there is invisible to other machines and
  agents. Put shared context in `AGENTS.md`, `.agents/skills/`, `specs/` or `docs/`.
