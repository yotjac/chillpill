# AGENTS.md — how to work in this repo

Read this first. It is short on purpose; deeper material is linked, not inlined.

## What this is

Chillpill is a single-module Android app (Kotlin, Jetpack Compose, `:app`, package
`com.chillpill`) that reduces screen time: an AccessibilityService intercepts launches of
user-chosen "restricted" apps, shows a block screen with a wait timer, then grants a
time-limited grace period, all driven by one state machine (the session engine). All data is local (DataStore +
Room). No network layer, no DI framework. Unit tests cover the pure session engine (`service/engine/`).

## Where context lives (load in this order, only as needed)

1. `AGENTS.md` (this file) — rules, commands, gotchas. Always loaded.
2. `ARCHITECTURE.md` — the full mental model: package map, data layer, service flows,
   navigation, concurrency. Read the relevant section before touching a subsystem; read
   all of it before any change to `service/`.
3. `.agents/skills/<name>/SKILL.md` — task-specific procedures. Load only the ones that
   match the task (see list below). Do not load skills that are irrelevant.
4. `specs/` — design records for past features (Proposal / Design / Tasks). Read the
   matching spec before modifying a feature that has one. Writing a new spec is optional.
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
./gradlew testDebugUnitTest      # unit tests (app/src/test: engine scenarios + fuzz, classifier, foreground resolver, trace replay, settings)
```

Release signing reads `keystore.properties` from the repo root (never committed). If it is
absent, release builds are unsigned; that is expected on agent machines.

The interesting behaviour (blocking, grace, re-intervention, suggestions) only runs on a
real device or emulator with the accessibility service and usage-access permission
enabled. It cannot be exercised from a unit test today, so for changes in `service/` or
the block/re-intervention activities, describe in the PR exactly how you verified
them manually, or leave the verification step for the human.

## Gotchas (things that have bitten before)

- **All blocking / grace / warning / suggestion state lives in `EngineCore`**
  (`service/engine/`, spec `specs/session-engine.md`). Activities, overlays and services send
  `Input`s to `SessionEngine` and render its StateFlows; they must never keep pipeline state of
  their own or decide anything. Nearly every past bug came from two components holding
  different beliefs about "which app is in front".
- `EngineCore` stays pure: no Android imports, no coroutines, no suspension, no clock. Every
  input carries its own event time (`elapsedRealtime`). Change behaviour there, with a test in
  `EngineCoreTest`; the fuzz test and invariant checker must stay green.
- Only one thread touches the core (`SessionEngine`'s `limitedParallelism(1)` scope). Keep it.
- Leaving a restricted app never extends grace. Grace is fixed at Continue; leaving only
  timestamps the exit for the 10 s return window of *Pause only* apps. Do
  not reintroduce "leaving refreshes grace": it let those apps skip the block forever (see
  `specs/no-reblock-apps-session-fix.md`).
- A restricted app's own window events in the first 2 s after its block appears (splash → main)
  are absorbed; later ones, and anything reached through the shade, count. Getting past a block is
  caught by the block activity's `BlockStopped` / `BlockClosed` — this must work **without usage
  access** (it is optional), so never rely on UsageStats probes alone for it. The block activities
  must keep reporting start / stop / close with the view model's `instance` id.
- A device bug becomes a test: pull `files/trace/trace-0.jsonl` (see ARCHITECTURE §6.2), put it in
  `app/src/test/resources/traces/`, assert the expected outcome in `TraceReplayTest`.
- Presence moves on **positive evidence only**: a foreign window is a foreground change if its
  class is an activity of its package (`WindowClassifier`). Dialogs, bottom sheets, popups, toasts
  and keyboards of other packages are overlays. Never go back to "everything unknown is an app":
  such a window does not pause the app under it, so no probe can ever undo the wrong "user left",
  and grace then ends silently instead of re-intervening (the Instagram-comments bug).
- The reverse also happens: a **real activity window that never took the foreground**. Android
  briefly resumes the launcher under the app in front (UsageStats: launcher RESUMED → PAUSED, the
  app never paused). Only UsageStats can tell, so `ForegroundResolver` tracks resumed state **per
  activity** (never "the last resumed package": that reports nothing during such a blip) and
  `EngineCore` P6b lets a probe that is ≥ 1 s younger than the last window event and names the
  *only* resumed package put presence back. Keep both conditions; without them a probe taken
  mid-switch or in split screen would move presence wrongly. Do not query UsageStats with a fixed
  look-back window either: an activity resumed before the window looks like "nothing in front".
- **Per-app modes** (`specs/per-app-modes.md`): a session keeps the `AppRule` it started with
  (`Session.rule`); read the mode from there, never from the current config, or a settings change
  mixes modes mid-session. Wait times are UI-only (`AppBlockViewModel` reads `AppPolicy`); the
  engine only gets mode, grace and "+10 s allowed", and clamps a picked length itself (1–90 min).
- **Link pass** (`specs/link-pass.md`): "opened from a link" is *inferred* (the app's activity came
  straight over another, non-home app's activity, no SystemUI window in between, seen through a
  window event). Keep it failing strict: when in doubt, block. A link-pass session is never
  PAUSE_ONLY and ends in the *initial* block, not re-intervention.
- `GraceNotificationService` is cosmetic. Never put timers or session logic back into it.
- Room is configured with `allowMainThreadQueries()` and
  `fallbackToDestructiveMigration()`. Bump the DB version when the schema changes; there
  are no migrations.
- Settings are draft-only until Save; reducing restrictions goes through
  `SettingsConfirmationScreen` with a wait. Don't add a shortcut that bypasses it.
- `.cursor/` is gitignored, so anything placed there is invisible to other machines and
  agents. Put shared context in `AGENTS.md`, `.agents/skills/`, `specs/` or `docs/`.
