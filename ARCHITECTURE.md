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

Additionally, when the user opens a **non-restricted** app and has used it for more than
20 minutes in the last 12 hours, ChillPill proactively shows a **suggestion popup** asking
whether to restrict that app. The user can accept, ignore (7-day cooldown), or permanently dismiss the
suggestion for that app.

Key concepts:

| Term | Meaning |
|------|---------|
| **Restricted app** | An app the user chose to restrict |
| **Wait time** | Seconds the user must wait on the block screen (default 12, range 1–7200) |
| **Grace period** | Minutes the user can use the app after waiting (default 5, range 1–1440) |
| **Re-intervention** | A second block shown when the grace period expires while the user is still in the restricted app; can be toggled per restricted app |
| **Suggestion popup** | A dialog shown when a non-restricted, non-excluded app is opened and has >20 min foreground time in the last 12 h; offers to restrict it |
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
├── SuggestRestrictionActivity.kt      Hosts suggestion-popup Compose dialog (separate task)
│
├── data/
│   ├── blockstate/
│   │   └── BlockSharedState.kt        SharedPreferences wrapper (currently unused)
│   ├── restricted/
│   │   └── RestrictedAppsRepository.kt DataStore for restricted package-name set
│   ├── settings/
│   │   ├── Settings.kt                Data class (waitTimeSeconds, gracePeriodMinutes)
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
│   ├── ChillpillAccessibilityService.kt  Detects app launches; blocks restricted apps, suggests non-restricted
│   └── GracePeriodService.kt          Foreground service that counts down the grace timer
│
└── ui/
    ├── appblock/
    │   ├── AppBlockScreen.kt          Block screen composable (wait animation, continue/go-home)
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
    │   ├── SettingsScreen.kt          Wait time, grace period inputs, restricted apps list
    │   └── SettingsViewModel.kt       Settings persistence, installed-app loading, search
    ├── statistics/
    │   ├── StatisticsScreen.kt        Time-range selector, per-app bar charts
    │   └── StatisticsViewModel.kt     Bucket aggregation, 15-second auto-refresh
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
- **Keys:** `wait_time_seconds` (Int, default 30), `grace_period_minutes` (Int, default 5), `setup_completed` (Boolean)
- **Exposed flows:** `settings: Flow<Settings>`, `setupCompleted: Flow<Boolean>`
- **Write methods:** `setWaitTimeSeconds`, `setGracePeriodMinutes`, `setSettings`, `setSetupCompleted`

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
succession for the same app. Suggestion eligibility (e.g. >20 min foreground in 12 h) is
computed via **UsageStatsManager** in the accessibility service.

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
| `graceValidUntilMillis` | `MutableMap<String, Long>` | Per-package grace expiry timestamp |
| `graceExpiredForPackage` | `String?` | Package whose grace expired while user was away |

Key methods: `isInGracePeriod(pkg)`, `setGraceValidUntil(pkg, millis)`,
`clearGraceForPackage(pkg)`, `setGraceExpiredForPackage(pkg)`.

### 6.2 ChillpillAccessibilityService

- Listens for `TYPE_WINDOW_STATE_CHANGED` events
- Maintains a single-threaded coroutine scope (`Dispatchers.Default.limitedParallelism(1)`)
- Decision flow on detecting a foreground change:
  1. If package **is** restricted:
     a. If `graceExpiredForPackage` matches → **re-intervention** (start `ReInterventionActivity`)
     b. If package is in grace period → allow
     c. Otherwise → record `OPEN_ATTEMPT`, start `AppBlockActivity`
  2. If package is **not** restricted → `handleNonRestrictedApp`:
     a. Skip if in `ExcludedApps.EXCLUDED_PACKAGES`
     b. Skip if the package has no launcher activity (`getLaunchIntentForPackage == null`)
     c. If not already suggested this session, query **UsageStatsManager** for foreground time of this package in the last 12 h; if ≥20 min, not ignored, not permanently excluded → launch `SuggestRestrictionActivity`
- **Self-package event filtering:** Events from the app's own package (e.g. when `AppBlockActivity`, `ReInterventionActivity`, or `SuggestRestrictionActivity` is shown) are handled before dispatching to `processEvent`: only `BlockingSharedState.currentForegroundPackage` is updated; `previousForegroundPackage` is left unchanged. This prevents the block screen from "resetting" the same-app guard, so when the restricted app fires a second `TYPE_WINDOW_STATE_CHANGED` during launch (e.g. splash-to-main transition), the service correctly treats it as the same app and does not show the block screen again. When the user taps "Go Home" from the block screen, `recordLeavingRestrictedApp` skips granting grace if `currentForegroundPackage` is the app's own package (user did not voluntarily leave the restricted app).
- Starts `GracePeriodService` when the user taps "Continue" on the block screen, or when the user taps "Restrict App" in the suggestion popup (so they can continue into the app without seeing the block screen that first time).

### 6.3 GracePeriodService (Foreground Service)

- Started by `AppBlockActivity` or `ReInterventionActivity` when the user taps "Continue" (initial block or re-intervention). This guarantees the grace timer runs after every block dismissal.
- Shows an ongoing notification with a countdown
- On expiry, determines whether the user is still in the restricted app via **UsageStatsManager** (`queryEvents` over the last 10 minutes to capture the most recent `ACTIVITY_RESUMED` event), falling back to `BlockingSharedState.currentForegroundPackage` if usage-stats query fails:
  - If user is still in the restricted app → records `GRACE_EXPIRED_WHILE_ACTIVE`, starts `ReInterventionActivity`
  - If user navigated away → records `GRACE_EXPIRED_WHILE_AWAY`, sets `BlockingSharedState.graceExpiredForPackage`
- Uses `serviceScope` on `Dispatchers.Main.immediate`

### Service ↔ Activity Data Flow

#### Restricted-app blocking flow

```
User opens restricted app
        │
        ▼
ChillpillAccessibilityService
   ├── Records OPEN_ATTEMPT in UsageEventsRepository
   ├── Starts AppBlockActivity (initial) or ReInterventionActivity (re-intervention), with package name
   └── Updates BlockingSharedState.currentForegroundPackage
        │
        ▼
AppBlockActivity / AppBlockViewModel  (initial block)  OR  ReInterventionActivity / AppBlockViewModel  (re-intervention)
   ├── Runs wait timer (from SettingsRepository.waitTimeSeconds)
   ├── On "Continue": records WAIT_COMPLETED, sets grace in BlockingSharedState,
   │   starts GracePeriodService, launches target app, finishes
   └── On "Go Home": records LEFT_APP, finishes
        │
        ▼ (if user continued)
GracePeriodService
   ├── Reads grace duration from SettingsRepository
   ├── Shows notification countdown
   └── On expiry: queries UsageStatsManager for actual foreground app;
       starts ReInterventionActivity (user still in app) or marks graceExpiredForPackage (user left)
```

#### Suggestion flow (non-restricted apps)

```
User opens non-restricted app (>20 min foreground in last 12 h)
        │
        ▼
ChillpillAccessibilityService.handleNonRestrictedApp()
   ├── Checks: not in ExcludedApps, has launcher activity,
   │   foreground time (UsageStatsManager) ≥ 20 min in last 12 h,
   │   not already suggested this session, not ignored/permanently excluded
   └── Starts SuggestRestrictionActivity (in its own task)
        │
        ▼
SuggestRestrictionActivity (dialog overlay, intercepted app visible behind it)
   ├── "Restrict App"    → adds pkg to RestrictedAppsRepository, sets grace in BlockingSharedState,
   │   starts GracePeriodService, clears tracker state, finishes (user continues into app; no block screen this time)
   ├── "Ignore (7 days)" → calls SuggestionRepository.ignoreForOneWeek(pkg), finishes
   └── "Never ask again about [app]" → calls SuggestionRepository.addPermanentlyExcluded(pkg), finishes
```

---

## 7. UI Layer

### 7.1 Navigation

All in-app navigation happens via a single `NavHost` in `MainActivity`:

| Route constant | Screen | Purpose |
|----------------|--------|---------|
| `Routes.SETUP` | `SetupScreen` | 4-step onboarding (welcome → permissions → app selection → done) |
| `Routes.HOME` | `HomeScreen` | Dashboard with today's stats, restricted apps, nav cards |
| `Routes.SETTINGS` | `SettingsScreen` | Edit wait time, grace period, restricted apps list |
| `Routes.APP_SELECTION` | `AppSelectionScreen` | Searchable installed-app list with checkboxes |
| `Routes.STATISTICS` | `StatisticsScreen` | Bar charts by time range (week/month/3mo/year) |

**Start destination logic** (in `MainActivity`):
- If `setupCompleted` is false → `SETUP`
- If intent has `EXTRA_OPEN_SETTINGS` → `SETTINGS`
- Otherwise → `HOME`

**Transitions:** Horizontal slide, 300 ms.

`AppBlockActivity` is a **separate Activity** (not part of the NavHost) launched by the
accessibility service for the **initial** block via Intent with `EXTRA_PACKAGE_NAME` and `EXTRA_IS_RE_INTERVENTION`.

`ReInterventionActivity` is another **separate Activity** used for **re-intervention** (when grace period
expires while the user is still in the app, or when they return after grace expired). It has a distinct UI:
animated fill background, large centered text with the app name in cyan ("still using &lt;app&gt;? this is your chance to stop"),
"Go back home" button visible from the start, and "Keep using the app" button appearing when the wait ends. It reuses
`AppBlockViewModel` for timer and event logic.

`SuggestRestrictionActivity` is another **separate Activity** launched by the accessibility
service when a non-restricted app is used frequently. It is themed as a translucent dialog
(`Theme.Chillpill.Dialog`) and runs in its own task (`taskAffinity=""`,
`excludeFromRecents="true"`) so the intercepted app remains visible behind the popup.

### 7.2 ViewModel Summary

| ViewModel | Key State Fields | Responsibilities |
|-----------|-----------------|------------------|
| `HomeViewModel` | `permissionsOk`, `showUsageAccessBanner`, `restrictedPackages`, `todayAttempts`, `todayEntered` | Check a11y/usage-access permissions, load today's aggregate stats |
| `SetupViewModel` | `currentStep` (0–3), `accessibilityGranted`, `usageAccessGranted`, `restrictedPackages`, `canAdvance` | Drive 4-step onboarding, validate each step before advancing |
| `SettingsViewModel` | `waitTimeSecondsInput`, `gracePeriodMinutesInput`, `restrictedAppsInfo`, `installedApps`, `appSearchQuery` | Persist settings, load/sort installed apps (by usage stats), filter search |
| `SettingsViewModel` (extended) | `expandedAppPackage`, `reInterventionDisabledPackages` | Track which restricted app card is expanded and which apps have re-intervention disabled |
| `StatisticsViewModel` | `selectedRange`, `stats: List<AppStatistic>`, `isLoading`, `focusedSeries` | Aggregate daily stats into buckets, auto-refresh every 15 s, legend focus |
| `AppBlockViewModel` | `phase` (WAITING/COMPLETED), `progress` (0→1), `openCount24h` | Run wait countdown, emit one-shot events (`RequestFinish`, `RequestGoHome`) via Channel |

All ViewModels expose state via `StateFlow` and screens collect it with
`collectAsStateWithLifecycle`.

### 7.3 Settings UI Details

- The **Restricted apps** section on `SettingsScreen` shows each restricted app as an expandable `Card` with the app icon, label, and an always-visible delete icon in the header row.
- The entire card header is tappable and includes a chevron icon to indicate that it can expand for additional settings; only one app card is expanded at a time.
- Expanding a card reveals a single toggle labeled **"Block again after grace"**, which controls whether re-intervention is enabled for that specific app.
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
| `SuggestRestrictionActivity` | Activity | No | Suggestion popup dialog (`taskAffinity=""`, `excludeFromRecents`) |
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
| `serviceScope` (`Dispatchers.Main.immediate + SupervisorJob`) | `GracePeriodService` | Main-thread scope for the foreground service |
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
8. **Separate Activity for block screen** — `AppBlockActivity` is launched outside the
   NavHost by the accessibility service for the initial block so it can overlay any app.
   Re-intervention uses a dedicated `ReInterventionActivity` with distinct UI (animated fill, cyan app name, different button visibility).
9. **Separate Activity for suggestion dialog** — `SuggestRestrictionActivity` runs in its
   own task so the intercepted app stays visible behind the translucent dialog.
10. **In-memory tracker + DataStore persistence for suggestions** — `AppOpenTracker`
   tracks which packages have had the suggestion shown this session (deduplication);
   `SuggestionRepository` persists user choices (7-day ignore and permanent exclusion) across restarts.

---

## 11. Resources

| Path | Contents |
|------|----------|
| `res/values/strings.xml` | App name, block-screen messages, suggestion-popup messages, accessibility service description |
| `res/values/colors.xml` | Color definitions |
| `res/values/themes.xml` | `Theme.Chillpill` (main), `Theme.Chillpill.Block` (block screen), `Theme.Chillpill.Dialog` (translucent suggestion popup) |
| `res/drawable/block_activity_background.jpg` | Block-screen background image |
| `res/drawable/block_background.xml` | Gradient drawable for block overlay |
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
