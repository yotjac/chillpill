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
| **Grace-expiry warning** | A small non-modal pill shown over the app for the last 10 s of a grace period, but only when re-intervention is enabled (i.e. the user is about to be kicked out). It offers one "+10 s" extension per grace window; once that is spent a second warning appears at 5 s, as a countdown only, which cannot be dismissed |
| **Suggestion popup** | A dialog shown when a non-restricted, non-excluded app is opened and has accumulated >30 min of foreground time in the last 12 h (cumulative, not a single sitting); offers to restrict it |
| **Excluded app** | An app that never triggers a suggestion popup — either hardcoded (browsers, utilities, etc.) or user-chosen via "Never ask me again about [app name]" |

---

## 2. Module & Build Setup

**Single-module project** — everything lives in `:app`.

| File | Role |
|------|------|
| `build.gradle.kts` (root) | Declares plugin versions only (AGP 8.2.2, Kotlin 1.9.22, KSP 1.9.22-1.0.17) |
| `settings.gradle.kts` | Includes `:app`, root project name `Chillpill` |
| `app/build.gradle.kts` | App config: namespace `com.chillpill`, compileSdk 34, minSdk 24, targetSdk 34, Java 17, Compose BOM 2024.02.00, Kotlin compiler extension 1.5.8 |

**No version catalog** (`libs.versions.toml`) and **no convention plugins**; versions are
declared directly in `app/build.gradle.kts`.

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
│   ├── blockstate/
│   │   └── BlockSharedState.kt        SharedPreferences wrapper (currently unused)
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
│   ├── BlockingSharedState.kt         In-memory singleton shared between services
│   ├── SessionPolicy.kt               Pure per-package session/grace/return-window decisions (unit-tested)
│   ├── ForegroundPackageQuery.kt      UsageStats lookup of the current foreground package (pause-aware)
│   ├── ChillpillAccessibilityService.kt  Detects app launches; blocks restricted apps, suggests non-restricted
│   ├── SuggestionOverlayManager.kt     Hosts suggestion-popup via TYPE_ACCESSIBILITY_OVERLAY
│   ├── GraceWarningOverlayManager.kt   Hosts the grace-expiry warning pill via TYPE_ACCESSIBILITY_OVERLAY
│   ├── GraceWarningView.kt            ComposeView subclass used as the pill's root (own class name)
│   └── GracePeriodService.kt          Foreground service that counts down the grace timer

app/src/test/java/com/chillpill/service/SessionPolicyTest.kt   JVM unit tests for SessionPolicy
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
    val blockSharedState    by lazy { BlockSharedState(this) }
    val settingsRepository  by lazy { SettingsRepository(this) }
    val blockBackgroundStore by lazy { BlockBackgroundStore(this) }
    val restrictedAppsRepository by lazy { RestrictedAppsRepository(this) }
    val appOpenTracker      by lazy { AppOpenTracker() }
    val suggestionRepository by lazy { SuggestionRepository(this) }
    val usageEventsRepository   by lazy { UsageEventsRepository(Room.databaseBuilder(...).build()) }
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
| `OPEN_ATTEMPT` | Accessibility service intercepts a restricted-app launch |
| `WAIT_COMPLETED` | User finishes the wait timer and taps "Continue" |
| `CONTINUED` | (reserved, not actively used) |
| `LEFT_APP` | User taps "Back to Home" on the block screen |
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
the last 12 h) is computed via **UsageStatsManager** in the accessibility service.

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

### 5.5 BlockSharedState (SharedPreferences — unused)

Instantiated in `ChillpillApp` but not referenced elsewhere. The active shared-state
mechanism is the in-memory `BlockingSharedState` object in the service layer.

---

## 6. Service Layer (Background)

### 6.1 BlockingSharedState (in-memory singleton)

An `object` that coordinates between the accessibility service and the grace-period service.

| Field | Type | Purpose |
|-------|------|---------|
| `currentForegroundPackage` | `String?` | Last foreground package set by the a11y service |
| `sessions` | `SessionPolicy` | Pure, unit-tested per-package bookkeeping: fixed grace deadline (set at Continue), active sessions, and "left at" timestamps for the 10 s return window of apps with re-intervention disabled (`service/SessionPolicy.kt`) |
| `graceWarning` | `StateFlow<GraceWarning?>` | The one channel for the grace-expiry warning pill: `GracePeriodService` decides *when* a warning is due (`setGraceWarning`), `ChillpillAccessibilityService` collects it and owns the window. `GraceWarning(packageName, deadlineWallMs, canExtend, dismissible)` |

Key methods: `isInGracePeriod(pkg)`, `clearGraceForPackage(pkg)`, `sessions.startSession(pkg, graceMs)`,
`sessions.onLeft(pkg)`, `sessions.onEnterFromElsewhere(pkg, reInterventionDisabled)`,
`sessions.hasActiveSession(pkg)`, `sessions.endSession(pkg)`, `setGraceWarning(warning)`,
`extendGrace(pkg)`.

`SessionPolicy` additionally exposes, for the warning pill (`specs/grace-expiry-warning.md`):
`graceRemainingMs(pkg)`, `graceDeadlineMs(pkg)`, `canExtendGrace(pkg)` and `extendGrace(pkg)`, which
pushes the deadline back by `GRACE_EXTENSION_MS` (10 s) at most `MAX_GRACE_EXTENSIONS` (1) times per
grace window — counted in `extensionsUsed`, reset by `startSession` and cleared by `endSession` /
`clearGrace`. The extension moves the **deadline** and nothing else: no session change, no exit
timestamp, no `onLeft`. `WARNING_LEAD_MS` (10 s) is how much of the window is left when the pill appears.

Invariant: **every block ends the session.** The accessibility service ends it on BLOCK
(`onEnterFromElsewhere`), and `GracePeriodService` ends it whenever grace runs out for an app with
re-intervention enabled, whether the user is still inside (re-intervention shown) or away (next
open shows the regular block). Only no-re-block apps keep a session past grace expiry.

### 6.2 ChillpillAccessibilityService

- Listens for `TYPE_WINDOW_STATE_CHANGED` events
- Maintains a single-threaded coroutine scope (`Dispatchers.Default.limitedParallelism(1)`)
- Decision flow on detecting a foreground change:
  1. If package **is** restricted:
     a. `sessions.onEnterFromElsewhere` → allow if in grace period, or (re-intervention disabled only) if the app has a live session and the user left it less than 10 s ago
     b. Otherwise → record `OPEN_ATTEMPT`, start `AppBlockActivity`
  2. If package is **not** restricted → `handleNonRestrictedApp`:
     a. Skip if in `ExcludedApps.EXCLUDED_PACKAGES`
     b. Skip if the package has no launcher activity (`getLaunchIntentForPackage == null`)
     c. If not already suggested this session, query **UsageStatsManager** for cumulative foreground time of this package in the last 12 h; if ≥30 min, not ignored, not permanently excluded → show suggestion via `SuggestionOverlayManager`
- **Self-package event filtering:** Events from the app's own package are handled before dispatching to `processEvent`. An own-package event whose class is **not** one of Chillpill's four activities comes from one of our overlay windows (grace warning pill, suggestion popup); those sit on top of the app the user is in, leave it in front, and are ignored outright — they must not set `currentForegroundPackage`, or the warning pill's "show only over the app the warning belongs to" gate would fail for the rest of the grace window. For the block screen and re-intervention only `BlockingSharedState.currentForegroundPackage` is updated; `previousForegroundPackage` is left unchanged. This prevents the block screen from "resetting" the same-app guard, so when the restricted app fires a second `TYPE_WINDOW_STATE_CHANGED` during launch (e.g. splash-to-main transition), the service correctly treats it as the same app and does not show the block screen again. Showing a block ends the package's session, so leaving from the block screen (Go Home, back, recents) can never earn a free re-entry, whatever order the window events arrive in. `MainActivity` and `ChillpillSettingsActivity` are different: they are real destinations (notification tap, launcher), so their events call `sessions.onLeft(prev)` and set `previousForegroundPackage` to our package, and coming back to the restricted app is re-evaluated like any other entry.
- **Leaving a restricted app never grants or extends grace.** Grace is "N minutes after Continue". `recordLeavingRestrictedApp` only calls `sessions.onLeft(prev)`, which records when the user left. That timestamp matters solely for apps with **"Block again after grace" off**: their session outlives the grace window while the user stays inside, and returning within `SessionPolicy.RETURN_WINDOW_MS` (10 s) of leaving keeps it; a longer absence shows the regular block on the next open. (Replaces the earlier "leaving refreshes grace" behaviour, which let these apps skip the block indefinitely — see `specs/no-reblock-apps-session-fix.md`.)
- **Overlay windows are not exits — while there is a session.** Window events from `com.android.systemui` (shade, volume/power dialogs, keyguard) and from keyboard windows of enabled input methods are ignored when `previousForegroundPackage` has a live session (`sessions.hasActiveSession`). A keyboard *package* is not enough: apps that ship an IME (SwiftKey, Grammarly, Samsung Keyboard) also have real activities, so an event whose class resolves to an activity of that package (`PackageManager.getActivityInfo`) is treated as a normal app; the manifest declares `<queries>` for `android.view.InputMethod` so `enabledInputMethodList` is complete on API 30+. Without a session (block screen up, non-restricted app) the overlay counts as a foreground change, so block → shade → tap-notification re-evaluates the restricted app instead of hitting the same-app guard. Because the lock screen is ignored, an `ACTION_SCREEN_OFF` receiver treats screen-off as leaving the current app and resets `previousForegroundPackage`; `ACTION_USER_PRESENT` then re-evaluates the package `ForegroundPackageQuery` reports if no window event arrived since unlock. A `screenOffGeneration` counter stops a `processEvent` that was suspended on a DataStore read across the screen-off from writing its stale package back.
- Dismissing `AppBlockActivity` / `ReInterventionActivity` via a system gesture (`onUserLeaveHint` — e.g. swipe-to-recents, pulling the notification shade) records a `LEFT_APP` event for stats accuracy, the same as tapping "Go Home" explicitly, but does **not** force-navigate anywhere; the OS is already handling where focus goes. Back is routed through `viewModel.onGoHome()` so it records `LEFT_APP` exactly once, like the button; a `leavingByButton` flag keeps `onUserLeaveHint` from double-counting those paths.
- Starts `GracePeriodService` when the user taps "Continue" on the block screen, or when the user taps "Restrict App" in the suggestion popup (so they can continue into the app without seeing the block screen that first time).
- **Owns the grace-expiry warning pill** (`GraceWarningOverlayManager`), because only an accessibility service may add a `TYPE_ACCESSIBILITY_OVERLAY` window. It collects `BlockingSharedState.graceWarning` on its own `Dispatchers.Main.immediate` scope (`graceWarningScope`, separate from `eventProcessorScope`) and shows the pill only while `currentForegroundPackage` is the warning's package; every foreground change (`processEvent`, and the overlay branch when it counts as one), `ACTION_SCREEN_OFF` and `onDestroy` take it down. The manager is deliberately **not** `SuggestionOverlayManager`: `processEvent` skips all event handling while that manager's `isShowing` is true, which would stop other restricted apps from being blocked for the pill's whole lifetime. The pill's `isShowing` is private to its manager and never consulted by the event pipeline. "+10 s" calls `BlockingSharedState.extendGrace(pkg)` and, on success, records `GRACE_EXTENDED`.
- **The pill emits no accessibility events.** Its window is non-focusable, so Android should never fire `TYPE_WINDOW_STATE_CHANGED` for it; and if one arrives anyway it is ignored by the overlay rule above, whatever class name it carries. Otherwise `currentForegroundPackage` would become `com.chillpill`, which both hides the pill for the rest of the window and could make the `ForegroundPackageQuery` fallback in `onGraceExpired` treat the user as "away".

### 6.3 GracePeriodService (Foreground Service)

- Started by `AppBlockActivity` or `ReInterventionActivity` when the user taps "Continue" (initial block or re-intervention), or by `ChillpillAccessibilityService` when the user taps "Restrict App" in the suggestion popup. This guarantees a grace timer runs after every block dismissal / new restriction.
- Shows a single ongoing notification while any grace window is being monitored.
- **Monitors one coroutine job per restricted package** (keyed in an internal `graceJobs` map), so grace for multiple restricted apps can be tracked concurrently — starting grace for App B no longer cancels or silently stops monitoring App A's grace. Starting grace again for the *same* package (e.g. re-intervention's "Keep using the app") cancels only that package's own prior job before starting a new one.
- Each job does **not** run a local countdown. It polls `BlockingSharedState.sessions.graceRemainingMs(pkg)` once per second and calls `onGraceExpired` when it reaches 0. The deadline is fixed at Continue; it only moves when the user passes a block again, which also restarts the job. Launching re-intervention ends the session (`sessions.endSession`).
- **Grace-expiry warning.** With re-intervention *enabled* for the package (the disabled set is read once when the job starts), the job publishes a `BlockingSharedState.GraceWarning` once the remaining time drops below the lead time — but only while that package is the current foreground one, so with two apps in their last seconds the foreground one gets the single warning slot and the other leaves it alone. There are two warnings with different lead times, chosen per tick by `canExtendGrace`: the first at `SessionPolicy.WARNING_LEAD_MS` (10 s), offering "+10 s" and dismissible; and, once the extension has been spent, a second at `SessionPolicy.FINAL_WARNING_LEAD_MS` (5 s) with neither button, which reappears even if the user tapped the first one away. Between the two there is no pill. The warning is cleared again if an extension pushes the remaining time back above the lead time, always just before `onGraceExpired` runs (so the pill is gone before `ReInterventionActivity` appears), in the job's `finally` (cancellation, e.g. grace restarted for that package), and in `onDestroy`. `onGraceExpired` itself is unchanged: an ignored or spent pill expires exactly as before. See `specs/grace-expiry-warning.md`.
- `serviceScope` uses a `SupervisorJob` plus a `CoroutineExceptionHandler` (mirroring `ChillpillAccessibilityService`'s `eventProcessorScope`), so an uncaught failure while monitoring one package's grace is logged rather than silently killing every other package's grace-monitoring job or crashing the process.
- On expiry for a given package, determines whether the user is still in that app via `ForegroundPackageQuery` (**UsageStatsManager** `queryEvents` over the last 10 minutes, tracking `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` pairs so a package that resumed and then paused — e.g. the screen was locked — is correctly treated as *not* currently foreground), falling back to `BlockingSharedState.currentForegroundPackage` if the usage-stats query is inconclusive:
  - If **re-intervention is disabled** for that package (`RestrictedAppsRepository.reInterventionDisabledPackages`) → records `GRACE_EXPIRED_WHILE_ACTIVE`/`GRACE_EXPIRED_WHILE_AWAY` (whichever matches) and stops; no block or re-intervention screen is shown, and the user is not re-blocked until they next leave and re-enter the app.
  - Else, if user is still in the restricted app → records `GRACE_EXPIRED_WHILE_ACTIVE`, starts `ReInterventionActivity` (re-intervention **only** happens at this moment)
  - Else, if user navigated away → records `GRACE_EXPIRED_WHILE_AWAY` and ends the session (`sessions.endSession`); their next open of that app shows the **regular** block screen
- The service stops itself (`stopSelf()`) once its `graceJobs` map is empty *and* no `startGraceTimer` call is still in flight (`startsInFlight`). The second condition matters because a job cancelled to make room for its replacement runs its cleanup on a background thread, possibly before the replacement is registered; stopping there would cancel `serviceScope` and with it the replacement, leaving an app whose grace never expires and which never warns.

### Service ↔ Activity Data Flow

#### Restricted-app blocking flow

```
User opens restricted app
        │
        ▼
ChillpillAccessibilityService
   ├── If in grace period (or, re-intervention disabled: left < 10 s ago with a live session): allow
   ├── Else: record OPEN_ATTEMPT, start AppBlockActivity (never ReInterventionActivity from here)
   └── Updates BlockingSharedState.currentForegroundPackage
        │
        ▼
AppBlockActivity / AppBlockViewModel  (initial block only from a11y service)
   ├── Runs wait timer (from SettingsRepository.waitTimeSeconds)
   ├── On "Continue": records WAIT_COMPLETED, starts the session + grace in BlockingSharedState.sessions,
   │   starts GracePeriodService, launches target app, finishes
   ├── On "Go Home" or Back: records LEFT_APP, finishes
   └── On system-gesture dismissal (onUserLeaveHint): records LEFT_APP (stats only), finishes — no forced navigation
        │
        ▼ (if user continued)
GracePeriodService
   ├── Starts (or restarts) a monitoring job for this specific package; other packages'
   │   jobs are unaffected
   ├── Shows/updates the shared ongoing notification
   ├── Polls BlockingSharedState.sessions.graceRemainingMs(pkg) every second rather than running
   │   a local countdown (the deadline is fixed at Continue; leaving does not move it)
   ├── In the last 10 s, with re-intervention enabled, publishes a GraceWarning so the
   │   accessibility service can show the warning pill ("+10 s" moves the deadline, nothing else)
   └── On expiry: queries UsageStatsManager for actual foreground app (pause-aware);
       if re-intervention disabled for pkg → record event only, no screen shown;
       else if user still in app → start ReInterventionActivity (only place re-intervention is shown);
       else if user left → record GRACE_EXPIRED_WHILE_AWAY, end the session (next open gets regular block)
```

#### Suggestion flow (non-restricted apps)

```
User opens non-restricted app (>30 min cumulative foreground time in last 12 h)
        │
        ▼
ChillpillAccessibilityService.handleNonRestrictedApp()
   ├── Checks: not in ExcludedApps, has launcher activity,
   │   cumulative foreground time (UsageStatsManager) ≥ 30 min in last 12 h,
   │   not already suggested this session, not ignored/permanently excluded
   └── Shows suggestion via SuggestionOverlayManager (accessibility overlay)
        │
        ▼
Suggestion overlay (accessibility overlay, intercepted app visible behind it)
   ├── "Restrict App"    → adds pkg to RestrictedAppsRepository, starts the session + grace in BlockingSharedState.sessions,
   │   starts GracePeriodService, clears tracker state, dismisses overlay (user continues into app; no block screen this time)
   ├── "Ignore (7 days)" → calls SuggestionRepository.markIgnored(pkg), clears tracker state, dismisses overlay
   └── "Never ask again about [app]" → calls SuggestionRepository.addPermanentlyExcluded(pkg), clears tracker state, dismisses overlay
```

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
accessibility service for the **initial** block via Intent with `EXTRA_PACKAGE_NAME` and `EXTRA_IS_RE_INTERVENTION`.

`ReInterventionActivity` is another **separate Activity** used for **re-intervention** (when grace period
expires while the user is still in the app, or when they return after grace expired). It has a distinct UI:
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
| `AppBlockViewModel` | `phase` (WAITING/COMPLETED), `progress` (0→1), `openCount24h`, `blockBackground` (nullable until read) | Run wait countdown, expose the chosen background, emit one-shot events (`RequestFinish`, `RequestGoHome`) via Channel |

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
| `GracePeriodService` | Service | No | Grace-period foreground timer |

The manifest also declares a `<queries>` block for launcher intents (used to list
installed apps).

---

## 9. Concurrency Model

| Scope / Dispatcher | Where | Why |
|---------------------|-------|-----|
| `viewModelScope` | All ViewModels | Standard lifecycle-aware coroutine scope |
| `eventProcessorScope` (`Dispatchers.Default.limitedParallelism(1)`) | `ChillpillAccessibilityService` | Serialized event processing to avoid race conditions |
| `serviceScope` (`Dispatchers.Main.immediate + SupervisorJob` + `CoroutineExceptionHandler`) | `GracePeriodService` | Parent scope for the service; each package's grace-monitoring job is launched on `Dispatchers.Default` from here (kept in a `graceJobs: Map<String, Job>`) so one package's failure or cancellation can't affect another's |
| `graceWarningScope` (`Dispatchers.Main.immediate + SupervisorJob` + `CoroutineExceptionHandler`) | `ChillpillAccessibilityService` | Collecting `BlockingSharedState.graceWarning` and adding/removing the pill window, which must happen on the main thread and must not queue behind the (possibly suspended) event pipeline |
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
7. **In-memory object for cross-service state** — `BlockingSharedState` is a Kotlin
   `object` (singleton) shared between the accessibility service and grace-period service.
8. **All user-facing text lives in `strings.xml`** — no string literals in Composables, including
   `contentDescription`s. Display labels on enums and data classes are `@StringRes` ids
   (`TimeRange.labelRes`, `BuiltInBackground.labelRes`), resolved with `stringResource` at the call
   site. Counted text uses `<plurals>` + `pluralStringResource`, never `%d` in a plain string.
   Brand name is spelled **Chillpill**. Headings and buttons are sentence case.
9. **Separate Activity for block screen** — `AppBlockActivity` is launched outside the
   NavHost by the accessibility service for the initial block so it can overlay any app.
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

After installing, the app requires:
1. **Accessibility service** permission (to detect app launches)
2. **Usage access** permission (to sort apps by usage in the selection screen)
3. **Notification** permission on Android 13+ (for the grace-period countdown notification)

The setup wizard guides the user through granting these permissions.
