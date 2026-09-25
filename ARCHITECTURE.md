# Chillpill — Technical & Architectural Overview

> **Purpose of this document:** Give any developer or AI agent a complete mental model of the
> codebase so they can navigate, modify, or extend it without reading every source file.

---

## 1. What the App Does

Chillpill is a screen-time reduction tool. When the user opens a restricted app, an
**AccessibilityService** intercepts the launch and presents a **block screen** with a
configurable wait timer. After the timer completes, the user may either go home or
continue into the app with a time-limited **grace period**. When the grace period expires
the block screen reappears.

Additionally, when the user opens a **non-restricted** app and has accumulated more than
30 minutes of foreground time for it in the last 12 hours (across any number of separate
sessions, not 30 continuous minutes), ChillPill proactively shows a **suggestion popup**
asking whether to restrict that app. Because the 30 minutes is a rolling 12-hour total, an
app you already used a lot earlier in the day can trigger the popup within seconds of a
brief later reopen — this is expected, not a bug, but is easy to misread as "popped up
almost instantly." The user can accept, ignore (7-day cooldown), or permanently dismiss the
suggestion for that app.

Key concepts:

| Term | Meaning |
|------|---------|
| **Restricted app** | An app the user chose to restrict |
| **Wait time** | Seconds the user must wait on the block screen (default 12, range 1–7200) |
| **Grace period** | Minutes the user can use the app after waiting (default 5, range 1–1440) |
| **Re-intervention** | A second block shown when the grace period expires while the user is still in the restricted app; can be toggled per restricted app |
| **Grace-expiry warning** | A small non-modal pill shown over the app for the last 10 s of a grace period, but only when re-intervention is enabled (i.e. the user is about to be kicked out). It offers one "+10 s" extension per grace window and can be tapped away. Whenever that first warning is not showing (extension spent, tapped away), a countdown-only warning that cannot be dismissed appears for the last 5 s |
| **Suggestion popup** | A dialog shown when a non-restricted, non-excluded app is opened and has accumulated >30 min of foreground time in the last 12 h (cumulative, not a single sitting); offers to restrict it |
| **Excluded app** | An app that never triggers a suggestion popup — either hardcoded (browsers, utilities, etc.) or user-chosen via "Never ask me again about [app name]" |

---

## 2. Module & Build Setup

**Single-module project** — everything lives in `:app`.

| File | Role |
|------|------|
| `build.gradle.kts` (root) | Declares plugin versions only (AGP 8.11.1, Kotlin 1.9.22, KSP 1.9.22-1.0.17) |
| `settings.gradle.kts` | Includes `:app`, root project name `Chillpill` |
| `app/build.gradle.kts` | App config: namespace `com.chillpill`, compileSdk 36, minSdk 24, targetSdk 36, Java 17, Compose BOM 2024.02.00, Kotlin compiler extension 1.5.8 |

**No version catalog** (`libs.versions.toml`) and **no convention plugins**; versions are
declared directly in `app/build.gradle.kts`.

**Toolchain floor.** AGP 8.11 requires **Gradle 8.13** (set in
`gradle/wrapper/gradle-wrapper.properties`) and **JDK 17**. AGP 8.9 and below cannot compile
against API 36 at all, so the AGP and `compileSdk` versions move together. Kotlin stays at
1.9.22 with Compose compiler extension 1.5.8 — going to Kotlin 2.x would mean replacing
`composeOptions` with the `org.jetbrains.kotlin.plugin.compose` plugin. See
`specs/target-sdk-36.md`.

### Key Dependencies

- Jetpack Compose (BOM 2024.02.00) + Material 3
- Navigation Compose 2.7.7
- Lifecycle (ViewModel, Runtime Compose) 2.7.0
- Room 2.6.1 (runtime + KSP compiler)
- DataStore Preferences 1.0.0
- Coroutines 1.8.1
- Gson 2.10.1

**No network layer** — no Retrofit, OkHttp, or remote API. All data is local.

### Signing

Release builds optionally read `keystore.properties` from the project root (not committed).

---

## 3. Package Layout

```
com.chillpill/
│
├── ChillpillApp.kt                    Application class, manual DI (repository creation)
├── MainActivity.kt                    Launcher, hosts Compose NavHost
├── ChillpillSettingsActivity.kt       Trampoline: opens MainActivity at the Settings route
├── AppBlockActivity.kt                Hosts block-screen Compose UI (separate from NavHost)
├── ReInterventionActivity.kt          Hosts re-intervention screen (separate Activity, distinct UI)
│
├── data/
│   ├── restricted/
│   │   └── RestrictedAppsRepository.kt DataStore for restricted package-name set
│   ├── settings/
│   │   ├── Settings.kt                Data class (waitTimeSeconds, gracePeriodMinutes, blockBackground) + BlockBackground
│   │   ├── BlockBackgrounds.kt        Registry of the block-screen images bundled in the APK
│   │   ├── BlockBackgroundStore.kt    Imports/stores the user's own block-screen photo in filesDir/backgrounds/
│   │   └── SettingsRepository.kt      DataStore for settings + setupCompleted flag
│   ├── suggestion/
│   │   ├── AppOpenTracker.kt          In-memory flag for per-session suggestion deduplication
│   │   ├── ExcludedApps.kt            Hardcoded Set<String> of never-suggest packages
│   │   └── SuggestionRepository.kt    DataStore for ignored (7-day) + permanently excluded packages
│   └── usage/
│       ├── UsageEvent.kt              Room @Entity
│       ├── UsageDatabase.kt           Room @Database (version 2)
│       ├── UsageEventsDao.kt          @Dao + query result types
│       └── UsageEventsRepository.kt   Aggregation queries, event recording, stat models
│
├── service/
│   ├── engine/                        The blocking pipeline (see §6, specs/session-engine.md)
│   │   ├── EngineModel.kt             Input, Effect, AppState, Presence, Config, WarningUi, SuggestionUi (pure)
│   │   ├── WindowClassifier.kt        What a window event means: app / own UI / overlay (pure)
│   │   ├── EngineCore.kt              The state machine: presence, sessions, grace, warnings (pure, unit-tested)
│   │   ├── EngineTrace.kt             JSON-lines trace of inputs/effects + codec for replay (pure JVM)
│   │   ├── SessionEngine.kt           Android host: input channel, config, effects, StateFlows, wake-up timer
│   │   └── ProcessExitLogger.kt       Why the previous process died (API 30+), for the trace
│   ├── ChillpillAccessibilityService.kt  Adapter: window/screen events in, pill + popup overlays out
│   ├── SuggestionEvaluator.kt         Whether an app qualifies for "restrict this?"; persists answers
│   ├── ForegroundPackageQuery.kt      UsageStats lookup of the foreground package + its RESUME time
│   ├── SuggestionOverlayManager.kt     Hosts suggestion-popup via TYPE_ACCESSIBILITY_OVERLAY
│   ├── GraceWarningOverlayManager.kt   Hosts the grace-expiry warning pill via TYPE_ACCESSIBILITY_OVERLAY
│   ├── GraceWarningView.kt            ComposeView subclass used as the pill's root (own class name)
│   └── GraceNotificationService.kt    Ongoing notification while grace runs (cosmetic, no timers)

app/src/test/java/com/chillpill/service/engine/   JVM tests: EngineCoreTest (scenarios), EngineFuzzTest,
                                                  WindowClassifierTest, TraceReplayTest (+ resources/traces/)
app/src/test/java/com/chillpill/ui/settings/RestrictionReducingTest.kt   JVM unit tests for isRestrictionReducing
│
└── ui/
    ├── appblock/
    │   ├── AppBlockScreen.kt          Block screen composable (wait animation, continue/go-home, background image)
    │   └── AppBlockViewModel.kt       Wait timer logic, event recording
    ├── reintervention/
    │   └── ReInterventionScreen.kt    Re-intervention composable (animated fill, cyan app name, go-home + keep-using)
    ├── common/
    │   └── AppIcon.kt                 Reusable composable: displays an app's icon
    ├── home/
    │   ├── HomeScreen.kt              Dashboard: greeting, today's stats, restricted apps, nav cards
    │   └── HomeViewModel.kt           Permissions check, today's attempt/entered stats
    ├── navigation/
    │   └── NavGraph.kt                Route constants, ChillpillNavHost, slide transitions
    ├── setup/
    │   ├── SetupScreen.kt             4-step onboarding wizard
    │   └── SetupViewModel.kt          Step state, permission tracking, advance logic
    ├── settings/
    │   ├── AppSelectionScreen.kt      Searchable list of installed apps with checkboxes
    │   ├── BlockBackgroundPicker.kt    Thumbnail row for choosing the block-screen image
    │   ├── SettingsConfirmationScreen.kt  Waiting screen before saving restriction-reducing changes
    │   ├── SettingsScreen.kt          Wait time, grace period inputs, restricted apps list, Save button
    │   └── SettingsViewModel.kt       Draft state, deferred save, confirmation timer, installed-app loading
    ├── statistics/
    │   ├── StatisticsScreen.kt        Time-range selector, per-app bar charts
    │   └── StatisticsViewModel.kt     Bucket aggregation, 15-second auto-refresh
    ├── gracewarning/
    │   └── GraceWarningPill.kt         Countdown pill with the single "+10 s" (grace-expiry warning)
    ├── suggestion/
    │   └── SuggestRestrictionScreen.kt  Dialog composable (Restrict / Ignore / Never ask again)
    └── theme/
        ├── Theme.kt                   ChillpillTheme (light/dark), color schemes, shapes
        └── Type.kt                    Typography (bodyLarge, titleLarge, labelLarge)
```

---

## 4. Architecture Pattern

**MVVM with Compose** — no Clean Architecture layers, no domain/use-case layer,
no repository interfaces.

```
┌──────────────────────────────────────────────────────────┐
│  UI Layer (Compose screens)                              │
│  Observe ViewModel StateFlows via collectAsStateWithLC   │
└────────────────────────┬─────────────────────────────────┘
                         │ calls
┌────────────────────────▼─────────────────────────────────┐
│  ViewModels                                              │
│  Hold MutableStateFlow, call repositories directly       │
└────────────────────────┬─────────────────────────────────┘
                         │ calls
┌────────────────────────▼─────────────────────────────────┐
│  Repositories (concrete classes)                         │
│  SettingsRepository · RestrictedAppsRepository            │
│  UsageEventsRepository · SuggestionRepository             │
└────────────────────────┬─────────────────────────────────┘
                         │ reads/writes
┌────────────────────────▼─────────────────────────────────┐
│  Storage                                                 │
│  DataStore Preferences · Room (UsageDatabase)            │
└──────────────────────────────────────────────────────────┘
```

### Dependency Injection

Manual DI via `ChillpillApp`:

```kotlin
// ChillpillApp.kt
class ChillpillApp : Application() {
    val settingsRepository  by lazy { SettingsRepository(this) }
    val blockBackgroundStore by lazy { BlockBackgroundStore(this) }
    val restrictedAppsRepository by lazy { RestrictedAppsRepository(this) }
    val appOpenTracker      by lazy { AppOpenTracker() }
    val suggestionRepository by lazy { SuggestionRepository(this) }
    val usageEventsRepository   by lazy { UsageEventsRepository(Room.databaseBuilder(...).build()) }
    val sessionEngine       by lazy { SessionEngine(this) }   // the blocking pipeline, see §6
}
```

ViewModels receive `ChillpillApp` through a `ViewModelProvider.Factory` and access its
repository properties. No Hilt, Dagger, or Koin.

---

## 5. Data Layer Deep Dive

### 5.1 SettingsRepository (DataStore)

- **Store name:** `"settings"`
- **Keys:** `wait_time_seconds` (Int, default 12), `grace_period_minutes` (Int, default 5), `setup_completed` (Boolean), `block_background` (String, default absent)
- **Exposed flows:** `settings: Flow<Settings>`, `setupCompleted: Flow<Boolean>`
- **Write methods:** `setWaitTimeSeconds`, `setGracePeriodMinutes`, `setBlockBackground`, `setSettings`, `setSetupCompleted`
- **`block_background`** encodes the block-screen image as `builtin:<id>` or `custom:<fileName>`, decoded by `BlockBackground.decode`. Anything missing, unknown or containing path separators falls back to the default, so older installs need no migration.

### 5.1.1 BlockBackgroundStore (files)

- **Location:** `filesDir/backgrounds/`, one JPEG per stored photo (`custom_<timestamp>.jpg`), written via a `.tmp` file + rename.
- **Import:** `importImage(uri)` copies the photo picker's URI (a temporary grant) into app storage, sampled and scaled to ≤ 2048 px on the long side and rotated/mirrored per its EXIF orientation. Every import writes a *new* file name, so the block screen can never read a half-written file.
- **Cleanup:** `cleanup(keep)` deletes everything except `keep`, skipping `.tmp` files younger than 60 s so a concurrent import is not killed. Called only from `SettingsViewModel` when `draftOnly = true` (on init and after a successful save) — no other screen touches these files.
- **`latestFileName()`** returns the most recently stored photo, which is what keeps the user's photo on offer in the picker while a bundled image is selected.

### 5.2 RestrictedAppsRepository (DataStore)

- **Store name:** `"restricted_apps"`
- **Keys:** `package_names` (String Set), `re_intervention_disabled` (String Set of package names that skip re-intervention)
- **Exposed flows:** `restrictedPackages: Flow<Set<String>>`, `reInterventionDisabledPackages: Flow<Set<String>>`
- **Write methods:** `setRestricted(packageNames: Set<String>)`, `addRestricted(packageName: String)`, `setReInterventionDisabled(packageNames: Set<String>)`

### 5.3 UsageEventsRepository (Room)

**Entity** `UsageEvent`:
| Column | Type | Notes |
|--------|------|-------|
| id | Long | Auto-generated PK |
| packageName | String | |
| timestamp | Long | epoch millis |
| eventType | String | one of the `UsageEventType` constants |
| sessionStartTime | Long? | nullable |

**Event types** (`UsageEventType` constants):
| Constant | When recorded |
|----------|---------------|
| `OPEN_ATTEMPT` | The engine shows a block or re-intervention screen |
| `WAIT_COMPLETED` | User finishes the wait timer and taps "Continue" |
| `CONTINUED` | (reserved, not actively used) |
| `LEFT_APP` | A block screen closes without Continue (Go Home, back, gesture) — once per block |
| `GRACE_EXPIRED_WHILE_ACTIVE` | Grace timer ends while user is still in the restricted app |
| `GRACE_EXPIRED_WHILE_AWAY` | Grace timer ends while user has navigated away |
| `GRACE_EXTENDED` | User tapped "+10 s" on the grace-expiry warning pill |

**Key DAO queries** (`UsageEventsDao`):
- `getEventsSince(packageName, since)` → `Flow<List<UsageEvent>>`
- `countEventsSince(packageName, eventType, since)` → `Int`
- `countEventsGrouped(packageNames, since)` → `List<EventCountRow>`
- `countEventsGroupedByDay(packageNames, since)` → `List<DailyEventCountRow>`

**Repository helper models:**
- `AppStats(openAttempts: Int, continueCount: Int)` — aggregate per package
- `DayStats(dayBucket: String, attempts: Int, entered: Int)` — daily aggregate

### 5.4 Suggestion Data Layer

#### AppOpenTracker (in-memory)

In-memory set of package names for which the suggestion dialog has already been shown in the
current process. Used to avoid showing the suggestion popup multiple times in rapid
succession for the same app. Suggestion eligibility (>30 min cumulative foreground time in
the last 12 h) is computed via **UsageStatsManager** in `SuggestionEvaluator`.

| Method | Purpose |
|--------|---------|
| `markSuggestionShown(pkg)` / `wasSuggestionShown(pkg)` | Per-session flag to avoid repeat popups |
| `clearSuggestionShown(pkg)` | Resets the flag (called when the user acts on the popup) |

#### ExcludedApps (hardcoded set)

`ExcludedApps.EXCLUDED_PACKAGES` is a `Set<String>` of package names for apps that should
never trigger a suggestion popup. Includes browsers, AI assistants, phone/contacts/SMS,
Spotify, WhatsApp, Google apps, the settings and clock apps, common device launchers, and
an Israeli emergency alert app.

#### SuggestionRepository (DataStore)

- **Store name:** `"suggestion_prefs"`
- **Keys:**
  - `ignored_packages` — JSON string (`Gson`) encoding a `Map<String, Long>` of package name → ignore-expiry timestamp (7-day cooldown)
  - `permanently_excluded_packages` — `StringSet` of packages the user chose "Never ask me again about [app]" for
- **Read methods:** `isIgnored(pkg): Boolean`, `isPermanentlyExcluded(pkg): Boolean`
- **Write methods:** `ignoreForOneWeek(pkg)`, `addPermanentlyExcluded(pkg)`

---

## 6. Service Layer (Background) — the session engine

Everything that decides whether a restricted app is blocked lives in **one pure state machine**,
`service/engine/EngineCore.kt`, hosted by `SessionEngine`. Android components only feed it inputs
and render its outputs. Full design, rule ids (P*, E*, A*, G*) and invariants:
`specs/session-engine.md`. Read it before changing anything in `service/`.

```
 a11y window event ─classify─┐                                          ┌──▶ ShowBlock → AppBlockActivity / ReInterventionActivity
 screen off / on / present ──┤      ┌─────────────────────────────┐     ├──▶ RecordUsage → UsageEventsRepository (Room)
 UsageStats probe result ────┼────▶ │ EngineCore.handle(input)    │ ────┼──▶ RequestProbe → ForegroundPackageQuery
 block activity lifecycle ───┤      │   presence  +  app states   │     ├──▶ EvaluateSuggestion / PersistSuggestionAnswer → SuggestionEvaluator
 Continue / +10 s / × / popup┤      │   → effects + derived state │     └──▶ StateFlows: warning, suggestion, graceActive
 config (DataStore, hot) ────┤      └─────────────────────────────┘            → pill, popup (a11y service), notification service
 wake-up timer (Tick) ───────┘
```

### 6.1 EngineCore (pure, `service/engine/`)

- No Android imports, no coroutines, no I/O, no clock of its own: every `Input` carries `at`, the
  `SystemClock.elapsedRealtime()` of when the thing *happened* (a11y `eventTime` and UsageStats
  RESUME times are converted). `handle(input)` mutates state synchronously and returns `Effect`s.
- **Presence** (`Presence(pkg, kind, since, screenOn)`): the single belief about which app is in
  front. Window events of kind `APP` / `OWN_MAIN_UI` / `OWN_BLOCK_UI` change it; overlays (SystemUI,
  keyboard windows, our pill and popup, and any other app's non-activity windows: dialogs, bottom
  sheets, popups, toasts) never do; screen-off clears it. An older signal never overrides a newer
  one (`at < since` is dropped).
- **Positive evidence only** (`WindowClassifier`): a foreign window counts as a foreground change
  only when its class is an activity of its package (`PackageManager.getActivityInfo`, cached in
  `SessionEngine`). A non-activity window does not pause the app beneath it, so a wrong "the user
  left" from one of them could never be repaired by a UsageStats probe (nothing newer ever
  resumes), and the session would end silently "while away". A probe (no class) is `APP`; a package
  we cannot see (visibility filtering: no launcher activity, not an IME) is `APP_OVERLAY` for a
  framework class (`android.*`) and `APP` otherwise.
- **Self-healing presence:** while any live session exists (grace running, user inside, or a
  no-re-block app left < 15 s ago) and the screen is on, a UsageStats probe runs
  every 5 s and 1.5 s before each grace deadline; after screen-on / unlock a burst of 4 probes runs
  (falling back to the app that was in front at screen-off). A probe is applied only when its RESUME
  is *newer* than the last window event, and dated to that RESUME, so the 10 s return window is
  measured to when the user really came back.
- **Per-app state** (`AppState`): Idle (no entry) · `Blocking(kind, shownAt, uiVisible, instance)` ·
  `Session(graceDeadline, extensionsUsed, firstWarningDismissed, graceOver, leftAt)`.
  `leftAt == null` exactly while the user is in the app (invariant I2).
- Entering a restricted app: grace valid → allow; no-re-block app back within 10 s → allow;
  otherwise block. Leaving never moves a deadline (I5). Grace expiry: no-re-block → `graceOver`,
  session stays; re-intervention app in front → re-intervention; away → Idle.
- **Block screen lifecycle:** the activity reports `BlockStarted/Stopped/Closed` with an instance id
  (from `AppBlockViewModel.instance`). The app's own window events in the first 2 s after its block
  appears (splash → main) are absorbed. Getting past the block (shade → notification, recents,
  heads-up) makes the app the presence again; when the block's `BlockStopped` / system
  `BlockClosed` arrives the core re-checks 700 ms later and re-shows it unless another app came to
  the front meanwhile (a Home swipe). Follow-up probes cover the rest. Go Home / Back never
  re-block. `LEFT_APP` is recorded exactly once, by state.
- **Derived state:** `warning(now)` (the pill: first warning with "+10 s" in the last 10 s unless
  extended or ×'d, otherwise a non-dismissible countdown in the last 5 s; only over the app in front,
  only with re-intervention on), `suggestion`, `graceActive()`, `nextWakeUp(now)`.
- `invariantViolations(now)` checks I1–I4; tests run it after every step, the host logs violations.

### 6.2 SessionEngine (host, `service/engine/SessionEngine.kt`)

- Lazy singleton in `ChillpillApp`, started by the accessibility service (`start()` is idempotent).
- One `Channel<Input>` drained on `Dispatchers.Default.limitedParallelism(1)`; `send()` is
  thread-safe and never blocks. Inputs queue until the first config (restricted apps +
  re-intervention-disabled set + grace length, from DataStore as a hot flow) has been applied.
- Executes effects off the engine thread (activities, Room, UsageStats, repositories).
- Publishes `warning`, `suggestion`, `graceActive` as `StateFlow`s and keeps one wake-up timer at
  `nextWakeUp`. A late wake-up (deep sleep) is harmless: the next input processes overdue deadlines
  first, with the same outcome.
- Also owns the `WindowClassifier` (with the IME-package cache and the cached
  `isActivityOf` lookup: true / false / null = package not visible).
- **Trace:** every input and effect is appended as JSON lines to `filesDir/trace/trace-{0,1}.jsonl`
  (`EngineTrace`, 512 KB rotation). Pull with
  `adb exec-out run-as com.chillpillapp cat files/trace/trace-0.jsonl`; drop it into
  `app/src/test/resources/traces/` and replay it in `TraceReplayTest`.
- **Process exit reasons** (API 30+, `ProcessExitLogger`) are logged at start and written to the
  trace, since sessions are in memory only and a restart makes the next event block.

### 6.3 ChillpillAccessibilityService (adapter)

- Classifies each `TYPE_WINDOW_STATE_CHANGED` and sends `Input.Window`; the screen receiver sends
  `ScreenOff` / `ScreenOn(keyguardLocked)` / `UserPresent`. No state of its own.
- Renders `engine.warning` via `GraceWarningOverlayManager` and `engine.suggestion` via
  `SuggestionOverlayManager` (`TYPE_ACCESSIBILITY_OVERLAY` windows can only be added by an
  accessibility service). Pill buttons send `ExtendTapped` / `WarningDismissed`; popup buttons send
  `SuggestionAnswered`. An accepted "+10 s" shows "10 s added" for 1.2 s (`WarningUi.extensionConfirmed`).
- The suggestion popup never pauses event handling; it closes when the user leaves its app.

### 6.4 Other adapters

- `SuggestionEvaluator`: whether a non-restricted app qualifies for the popup (excluded list,
  launcher activity, `AppOpenTracker` dedupe, ≥30 min cumulative foreground in 12 h via UsageStats,
  not ignored / excluded), and persisting the answer (Restrict adds the app to the restricted set).
- `ForegroundPackageQuery`: last un-paused RESUME in the last 10 min, with its timestamp.
- `GraceNotificationService`: the ongoing notification while `graceActive`; started by the engine on
  every false→true transition, stops itself with `stopSelfResult(lastStartId)` when grace ends.
  **Cosmetic only** — no timers, no session logic.
- `AppBlockActivity` / `ReInterventionActivity` + `AppBlockViewModel`: wait timer and UI; send
  `ContinueTapped`, `BlockClosed(GO_HOME | BACK | SYSTEM_GESTURE)`, `BlockStarted/Stopped`, and
  launch the target app after Continue.

---

## 7. UI Layer

### 7.1 Navigation

All in-app navigation happens via a single `NavHost` in `MainActivity`:

| Route constant | Screen | Purpose |
|----------------|--------|---------|
| `Routes.SETUP` | `SetupScreen` | 4-step onboarding (welcome → permissions → app selection → done) |
| `Routes.HOME` | `HomeScreen` | Dashboard with today's stats, restricted apps, nav cards |
| `Routes.SETTINGS_FLOW` | (nested graph) | Settings flow: shared ViewModel, deferred save |
| ↳ `Routes.SETTINGS` | `SettingsScreen` | Edit wait time, grace period, restricted apps list; Save button |
| ↳ `Routes.SETTINGS_APP_SELECTION` | `AppSelectionScreen` | Searchable installed-app list (draft, same ViewModel as Settings) |
| `Routes.APP_SELECTION` | `AppSelectionScreen` | Standalone app selection (e.g. from Setup); auto-saves on change |
| `Routes.STATISTICS` | `StatisticsScreen` | Bar charts by time range (week/month/3mo/year) |

**Nested settings graph:** `SETTINGS_FLOW` is a nested navigation graph with start destination `SETTINGS`. Both `SettingsScreen` and the in-flow app selection screen scope their `SettingsViewModel` to the graph's back stack entry so draft state is shared; nothing is persisted until the user taps Save on the settings screen.

**Start destination logic** (in `MainActivity`):
- If `setupCompleted` is false → `SETUP`
- If intent has `EXTRA_OPEN_SETTINGS` → `SETTINGS_FLOW`
- Otherwise → `HOME`

**Transitions:** Horizontal slide, 300 ms.

`AppBlockActivity` is a **separate Activity** (not part of the NavHost) launched by the
session engine for the **initial** block via Intent with `EXTRA_PACKAGE_NAME` and `EXTRA_IS_RE_INTERVENTION`.

`ReInterventionActivity` is another **separate Activity** used for **re-intervention** (when the grace period
expires while the user is still in the app; returning after grace expired shows the regular block). It has a distinct UI:
animated fill background, large centered text with the app name in cyan ("still using &lt;app&gt;? this is your chance to stop"),
"Go back home" button visible from the start, and "Keep using the app" button appearing when the wait ends. It reuses
`AppBlockViewModel` for timer and event logic.

The **suggestion popup** is shown as an accessibility overlay via `SuggestionOverlayManager`
(`TYPE_ACCESSIBILITY_OVERLAY`), so the intercepted app remains visible behind the overlay and
the popup can't be displaced by the target app's rapid activity/window changes.

### 7.2 ViewModel Summary

| ViewModel | Key State Fields | Responsibilities |
|-----------|-----------------|------------------|
| `HomeViewModel` | `permissionsOk`, `showUsageAccessBanner`, `restrictedPackages`, `todayAttempts`, `todayEntered` | Check a11y/usage-access permissions, load today's aggregate stats |
| `SetupViewModel` | `currentStep` (0–3), `accessibilityGranted`, `usageAccessGranted`, `restrictedPackages`, `canAdvance` | Drive 4-step onboarding, validate each step before advancing |
| `SettingsViewModel` | `waitTimeSecondsInput`, `gracePeriodMinutesInput`, `restrictedAppsInfo`, `installedApps`, `appSearchQuery`, `hasChanges`, `showConfirmationScreen`, `confirmationProgress`, `confirmationPhase` | Draft-only state (when `draftOnly=true`); load/sort installed apps; persist only on Save; run confirmation timer when saving restriction-reducing changes |
| `SettingsViewModel` (extended) | `expandedAppPackage`, `reInterventionDisabledPackages`, `blockBackground`, `customBackgroundFileName`, `backgroundImportFailed` | Track which restricted app card is expanded and which apps have re-intervention disabled. When `draftOnly=false` (e.g. Setup flow's app selection), restricted-app changes persist immediately. |
| `StatisticsViewModel` | `selectedRange`, `stats: List<AppStatistic>`, `isLoading`, `focusedSeries` | Aggregate daily stats into buckets, auto-refresh every 15 s, legend focus |
| `AppBlockViewModel` | `phase` (WAITING/COMPLETED), `progress` (0→1), `openCount24h`, `blockBackground` (nullable until read) | Run wait countdown, expose the chosen background, emit one-shot events (`RequestFinish`, `RequestGoHome`) via Channel; report Continue / close / start / stop to the session engine under a per-block `instance` id |

All ViewModels expose state via `StateFlow` and screens collect it with
`collectAsStateWithLifecycle`.

### 7.3 Settings UI Details

- **Deferred save:** Settings are not written to DataStore until the user taps **Save**. A **Save** button in the top bar (left, after the back arrow) is disabled when there are no changes and enabled when any draft change exists (wait time, grace period, restricted apps, or re-intervention toggles).
- **Restriction-reducing confirmation:** When the user taps Save, if the draft would *reduce* restrictions (grace period increased, wait time decreased, or any restricted app removed), a **settings confirmation screen** is shown instead of persisting immediately. It reuses the re-intervention visual style (black background, animated surface fill from bottom, centered message: "Take a breath before reducing your restrictions…"). The top button is **"back"** (dismiss confirmation, stay on settings); the bottom button **"save changes"** appears only after the current (persisted) wait-time countdown completes. **Back** cancels the confirmation; **save changes** persists and closes the confirmation. The rule is the pure function `isRestrictionReducing` (top of `SettingsViewModel.kt`, unit-tested); before applying it, Save re-reads the persisted values and merges restricted apps added meanwhile by the suggestion overlay into the draft. Empty/invalid wait or grace fields fall back to the saved value (not the app default) on focus loss. The screen sits inside `MainActivity`'s `safeDrawing` padding, so it extends itself back under the system bars (`extendIntoSystemBars`) and pads its own content by the raw insets.
- **Waiting screen image:** a `BlockBackgroundPicker` row between the configuration fields and the restricted apps list offers the bundled images from `BlockBackgrounds`, the user's own photo when one is stored, and a tile that opens the system photo picker (`ActivityResultContracts.PickVisualMedia` — no permission needed). The picked photo is imported immediately but only *selected in the draft*; the DataStore key is written on Save. Changing the image alone is never restriction-reducing, so it does not trigger the confirmation wait. A failed import surfaces as a snackbar and leaves the draft untouched. Selection is highlighted with the same pending colour as the wait-time field while unsaved.
- **Restricted apps** section on `SettingsScreen` shows each restricted app as an expandable `Card` with the app icon, label, and an always-visible delete icon in the header row.
- The entire card header is tappable and includes a chevron icon to indicate that it can expand for additional settings; only one app card is expanded at a time.
- Expanding a card reveals a single toggle labeled **"Block again after grace"**, which controls whether re-intervention is enabled for that specific app (draft only until Save).
- A horizontal divider between the header and expanded area uses the theme's `onSurface` color so it appears as a high-contrast white/black line depending on light or dark mode.

### 7.4 Theme

- `ChillpillTheme` wraps Material 3 `MaterialTheme` with custom light/dark color schemes
- Palette: Slate/Teal (Teal600 primary in light, Teal500 in dark, Slate900 backgrounds)
- `ChillpillShapes` provides `RoundedCornerShape` at 4–12 dp
- Typography uses `FontFamily.Default` with customized `bodyLarge` (16 sp), `titleLarge`
  (22 sp), `labelLarge` (14 sp)

---

## 8. Android Manifest Highlights

### Permissions

| Permission | Why |
|------------|-----|
| `FOREGROUND_SERVICE` | Grace-period countdown service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Required on API 34+ for the foreground service type |
| `PACKAGE_USAGE_STATS` | Sorting installed apps by recent usage in the selection screen |
| `POST_NOTIFICATIONS` | Grace-period countdown notification |

### Components

| Component | Type | Exported | Purpose |
|-----------|------|----------|---------|
| `MainActivity` | Activity | Yes (launcher) | Main Compose host |
| `ChillpillSettingsActivity` | Activity | Yes | Accessibility settings trampoline |
| `AppBlockActivity` | Activity | No | Block screen (initial) |
| `ReInterventionActivity` | Activity | No | Re-intervention screen (distinct UI, same wait/grace logic) |
| `ChillpillAccessibilityService` | Service | No | App-launch detection via a11y |
| `GraceNotificationService` | Service | No | Ongoing notification while grace runs (cosmetic) |

All four activities are locked to `android:screenOrientation="portrait"`. Android 16 ignores
that on displays ≥600dp wide, so `<application>` declares
`android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` as a temporary opt-out. That
property stops working once the app targets API 37 — adaptive layouts have to land before the
next target bump (`specs/target-sdk-36.md`, D3).

The manifest also declares a `<queries>` block for launcher intents (used to list
installed apps).

---

## 9. Concurrency Model

| Scope / Dispatcher | Where | Why |
|---------------------|-------|-----|
| `viewModelScope` | All ViewModels | Standard lifecycle-aware coroutine scope |
| engine thread (`Dispatchers.Default.limitedParallelism(1)` + `SupervisorJob`) | `SessionEngine` | The only thread that touches `EngineCore`; drains the input channel, runs the wake-up timer. Decisions never suspend |
| `io` (`Dispatchers.IO` + `SupervisorJob`) | `SessionEngine` | Executing effects: Room, UsageStats probes, suggestion evaluation, trace flushes |
| `uiScope` (`Dispatchers.Main.immediate`) | `ChillpillAccessibilityService` | Collecting `engine.warning` / `engine.suggestion` and adding/removing the overlay windows |
| `scope` (`Dispatchers.Main.immediate`) | `GraceNotificationService` | Watching `engine.graceActive` to stop itself |
| `CoroutineScope(Dispatchers.IO)` | `AppBlockActivity` | Collecting one-shot ViewModel events |

Room allows main-thread queries (`allowMainThreadQueries()`) and uses
`fallbackToDestructiveMigration()`.

---

## 10. Key Patterns & Conventions

1. **No Hilt / no DI framework** — repositories are lazy-initialized in `ChillpillApp`
   and passed to ViewModels through a factory that receives the `Application`.
2. **No repository interfaces** — all repositories are concrete classes.
3. **No domain/use-case layer** — ViewModels call repositories directly.
4. **StateFlow for UI state** — mutable state is private; immutable `StateFlow` is exposed.
5. **Channel for one-shot events** — `AppBlockViewModel` uses `Channel<AppBlockEvent>` for
   navigation actions (`RequestFinish`, `RequestGoHome`).
6. **DataStore for simple prefs, Room for structured data** — settings and restricted-app
   sets use DataStore; usage events use Room.
7. **One state machine for the blocking pipeline** — all blocking / grace / warning / suggestion
   state lives in `EngineCore`, fed through `SessionEngine`. Components send inputs and render its
   StateFlows; they never keep pipeline state of their own. In memory only: a process restart
   fails strict (the next event from a restricted app blocks).
8. **All user-facing text lives in `strings.xml`** — no string literals in Composables, including
   `contentDescription`s. Display labels on enums and data classes are `@StringRes` ids
   (`TimeRange.labelRes`, `BuiltInBackground.labelRes`), resolved with `stringResource` at the call
   site. Counted text uses `<plurals>` + `pluralStringResource`, never `%d` in a plain string.
   Brand name is spelled **Chillpill**. Headings and buttons are sentence case.
9. **Separate Activity for block screen** — `AppBlockActivity` is launched outside the
   NavHost by the session engine for the initial block so it can overlay any app.
   Re-intervention uses a dedicated `ReInterventionActivity` with distinct UI (animated fill, cyan app name, different button visibility).
10. **Accessibility overlay for suggestion dialog** — `SuggestionOverlayManager` shows the
   suggestion via `TYPE_ACCESSIBILITY_OVERLAY` so it can't be displaced by the target app.
11. **In-memory tracker + DataStore persistence for suggestions** — `AppOpenTracker`
   tracks which packages have had the suggestion shown this session (deduplication);
   `SuggestionRepository` persists user choices (7-day ignore and permanent exclusion) across restarts.

---

## 11. Resources

| Path | Contents |
|------|----------|
| `res/values/strings.xml` | **All** user-facing text: shared actions, block / re-intervention screens, service + accessibility descriptions, grace-expiry warning pill (`grace_warning_countdown` plurals, `grace_warning_extend`, `grace_warning_extended`, and the two content descriptions), suggestion popup, home, setup, settings, waiting-screen image picker, app selection, statistics. Includes the `block_attempts_last_24h` plurals resource |
| `res/values/colors.xml` | Color definitions |
| `res/values/themes.xml` | `Theme.Chillpill` (main), `Theme.Chillpill.Block` (block screen) |
| `res/values-v28/themes.xml` | `Theme.Chillpill.Block` with `windowLayoutInDisplayCutoutMode=shortEdges` so the fullscreen block windows are not letterboxed below the camera cutout |
| `res/drawable-nodpi/block_activity_background.jpg` | Default block-screen background image. **nodpi** so Android does not treat it as mdpi and upscale it in memory |
| `res/drawable/ic_launcher_foreground.xml` | Vector launcher foreground |
| `res/xml/accessibility_service_config.xml` | AccessibilityService configuration |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon definition |

---

## 12. Build & Run

```bash
# Debug build
./gradlew assembleDebug      # or gradlew.bat on Windows

# Install on connected device
./gradlew installDebug

# Release build (requires keystore.properties)
./gradlew assembleRelease
```

Builds need **JDK 17** and the Gradle 8.13 wrapper distribution; the first build after a
toolchain bump downloads it.

After installing, the app requires:
1. **Accessibility service** permission (to detect app launches)
2. **Usage access** permission (to sort apps by usage in the selection screen)
3. **Notification** permission on Android 13+ (for the grace-period countdown notification)

The setup wizard guides the user through granting these permissions.
