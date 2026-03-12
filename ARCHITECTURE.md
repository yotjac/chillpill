# Chillpill — Technical & Architectural Overview

> **Purpose of this document:** Give any developer or AI agent a complete mental model of the
> codebase so they can navigate, modify, or extend it without reading every source file.

---

## 1. What the App Does

Chillpill is a screen-time reduction tool. When the user opens a monitored app, an
**AccessibilityService** intercepts the launch and presents a **block screen** with a
configurable wait timer. After the timer completes, the user may either go home or
continue into the app with a time-limited **grace period**. When the grace period expires
the block screen reappears.

Key concepts:

| Term | Meaning |
|------|---------|
| **Monitored app** | An app the user chose to restrict |
| **Wait time** | Seconds the user must wait on the block screen (default 30, range 1–7200) |
| **Grace period** | Minutes the user can use the app after waiting (default 5, range 1–1440) |
| **Re-intervention** | A second block shown when the grace period expires while the user is still in the monitored app |

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
│
├── data/
│   ├── blockstate/
│   │   └── BlockSharedState.kt        SharedPreferences wrapper (currently unused)
│   ├── monitored/
│   │   └── MonitoredAppsRepository.kt DataStore for monitored package-name set
│   ├── settings/
│   │   ├── Settings.kt                Data class (waitTimeSeconds, gracePeriodMinutes)
│   │   └── SettingsRepository.kt      DataStore for settings + setupCompleted flag
│   └── usage/
│       ├── UsageEvent.kt              Room @Entity
│       ├── UsageDatabase.kt           Room @Database (version 2)
│       ├── UsageEventsDao.kt          @Dao + query result types
│       └── UsageEventsRepository.kt   Aggregation queries, event recording, stat models
│
├── service/
│   ├── BlockingSharedState.kt         In-memory singleton shared between services
│   ├── ChillpillAccessibilityService.kt  Detects monitored-app launches, starts block/grace
│   └── GracePeriodService.kt          Foreground service that counts down the grace timer
│
└── ui/
    ├── appblock/
    │   ├── AppBlockScreen.kt          Block screen composable (wait animation, continue/go-home)
    │   └── AppBlockViewModel.kt       Wait timer logic, event recording
    ├── common/
    │   └── AppIcon.kt                 Reusable composable: displays an app's icon
    ├── home/
    │   ├── HomeScreen.kt              Dashboard: greeting, today's stats, monitored apps, nav cards
    │   └── HomeViewModel.kt           Permissions check, today's attempt/entered stats
    ├── navigation/
    │   └── NavGraph.kt                Route constants, ChillpillNavHost, slide transitions
    ├── setup/
    │   ├── SetupScreen.kt             4-step onboarding wizard
    │   └── SetupViewModel.kt          Step state, permission tracking, advance logic
    ├── settings/
    │   ├── AppSelectionScreen.kt      Searchable list of installed apps with checkboxes
    │   ├── SettingsScreen.kt          Wait time, grace period inputs, monitored apps list
    │   └── SettingsViewModel.kt       Settings persistence, installed-app loading, search
    ├── statistics/
    │   ├── StatisticsScreen.kt        Time-range selector, per-app bar charts
    │   └── StatisticsViewModel.kt     Bucket aggregation, 15-second auto-refresh
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
│  SettingsRepository · MonitoredAppsRepository            │
│  UsageEventsRepository                                   │
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
    val monitoredAppsRepository by lazy { MonitoredAppsRepository(this) }
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

### 5.2 MonitoredAppsRepository (DataStore)

- **Store name:** `"monitored_apps"`
- **Key:** `package_names` (String Set)
- **Exposed flow:** `monitoredPackages: Flow<Set<String>>`
- **Write method:** `setMonitored(packageNames: Set<String>)`

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
| `OPEN_ATTEMPT` | Accessibility service intercepts a monitored-app launch |
| `WAIT_COMPLETED` | User finishes the wait timer and taps "Continue" |
| `CONTINUED` | (reserved, not actively used) |
| `LEFT_APP` | User taps "Back to Home" on the block screen |
| `GRACE_EXPIRED_WHILE_ACTIVE` | Grace timer ends while user is still in the monitored app |
| `GRACE_EXPIRED_WHILE_AWAY` | Grace timer ends while user has navigated away |

**Key DAO queries** (`UsageEventsDao`):
- `getEventsSince(packageName, since)` → `Flow<List<UsageEvent>>`
- `countEventsSince(packageName, eventType, since)` → `Int`
- `countEventsGrouped(packageNames, since)` → `List<EventCountRow>`
- `countEventsGroupedByDay(packageNames, since)` → `List<DailyEventCountRow>`

**Repository helper models:**
- `AppStats(openAttempts: Int, continueCount: Int)` — aggregate per package
- `DayStats(dayBucket: String, attempts: Int, entered: Int)` — daily aggregate

### 5.4 BlockSharedState (SharedPreferences — unused)

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
  1. Ignore non-monitored packages, launcher, system UI, own activities
  2. If `graceExpiredForPackage` matches → **re-intervention** (show block screen again)
  3. If package is in grace period → allow
  4. Otherwise → record `OPEN_ATTEMPT`, start `AppBlockActivity`
- Starts `GracePeriodService` when a grace period needs to begin
- Stops `GracePeriodService` when the user leaves the monitored app

### 6.3 GracePeriodService (Foreground Service)

- Shows an ongoing notification with a countdown
- On expiry:
  - If user is still in the monitored app → records `GRACE_EXPIRED_WHILE_ACTIVE`,
    starts `AppBlockActivity` as re-intervention
  - If user navigated away → records `GRACE_EXPIRED_WHILE_AWAY`,
    sets `BlockingSharedState.graceExpiredForPackage`
- Uses `serviceScope` on `Dispatchers.Main.immediate`

### Service ↔ Activity Data Flow

```
User opens monitored app
        │
        ▼
ChillpillAccessibilityService
   ├── Records OPEN_ATTEMPT in UsageEventsRepository
   ├── Starts AppBlockActivity (with package name + re-intervention flag)
   └── Updates BlockingSharedState.currentForegroundPackage
        │
        ▼
AppBlockActivity / AppBlockViewModel
   ├── Runs wait timer (from SettingsRepository.waitTimeSeconds)
   ├── On "Continue": records WAIT_COMPLETED, sets grace in BlockingSharedState, finishes
   └── On "Go Home": records LEFT_APP, finishes
        │
        ▼ (if user continued)
GracePeriodService
   ├── Reads grace duration from SettingsRepository
   ├── Shows notification countdown
   └── On expiry: either re-blocks or marks graceExpiredForPackage
```

---

## 7. UI Layer

### 7.1 Navigation

All in-app navigation happens via a single `NavHost` in `MainActivity`:

| Route constant | Screen | Purpose |
|----------------|--------|---------|
| `Routes.SETUP` | `SetupScreen` | 4-step onboarding (welcome → permissions → app selection → done) |
| `Routes.HOME` | `HomeScreen` | Dashboard with today's stats, monitored apps, nav cards |
| `Routes.SETTINGS` | `SettingsScreen` | Edit wait time, grace period, monitored apps list |
| `Routes.APP_SELECTION` | `AppSelectionScreen` | Searchable installed-app list with checkboxes |
| `Routes.STATISTICS` | `StatisticsScreen` | Bar charts by time range (week/month/3mo/year) |

**Start destination logic** (in `MainActivity`):
- If `setupCompleted` is false → `SETUP`
- If intent has `EXTRA_OPEN_SETTINGS` → `SETTINGS`
- Otherwise → `HOME`

**Transitions:** Horizontal slide, 300 ms.

`AppBlockActivity` is a **separate Activity** (not part of the NavHost) launched by the
accessibility service via Intent with `EXTRA_PACKAGE_NAME` and `EXTRA_IS_RE_INTERVENTION`.

### 7.2 ViewModel Summary

| ViewModel | Key State Fields | Responsibilities |
|-----------|-----------------|------------------|
| `HomeViewModel` | `permissionsOk`, `showUsageAccessBanner`, `monitoredPackages`, `todayAttempts`, `todayEntered` | Check a11y/usage-access permissions, load today's aggregate stats |
| `SetupViewModel` | `currentStep` (0–3), `accessibilityGranted`, `usageAccessGranted`, `monitoredPackages`, `canAdvance` | Drive 4-step onboarding, validate each step before advancing |
| `SettingsViewModel` | `waitTimeSecondsInput`, `gracePeriodMinutesInput`, `monitoredAppsInfo`, `installedApps`, `appSearchQuery` | Persist settings, load/sort installed apps (by usage stats), filter search |
| `StatisticsViewModel` | `selectedRange`, `stats: List<AppStatistic>`, `isLoading`, `focusedSeries` | Aggregate daily stats into buckets, auto-refresh every 15 s, legend focus |
| `AppBlockViewModel` | `phase` (WAITING/COMPLETED), `progress` (0→1), `openCount24h` | Run wait countdown, emit one-shot events (`RequestFinish`, `RequestGoHome`) via Channel |

All ViewModels expose state via `StateFlow` and screens collect it with
`collectAsStateWithLifecycle`.

### 7.3 Theme

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
| `AppBlockActivity` | Activity | No | Block screen |
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
6. **DataStore for simple prefs, Room for structured data** — settings and monitored-app
   sets use DataStore; usage events use Room.
7. **In-memory object for cross-service state** — `BlockingSharedState` is a Kotlin
   `object` (singleton) shared between the accessibility service and grace-period service.
8. **Separate Activity for block screen** — `AppBlockActivity` is launched outside the
   NavHost by the accessibility service so it can overlay any app.

---

## 11. Resources

| Path | Contents |
|------|----------|
| `res/values/strings.xml` | App name, block-screen messages, accessibility service description |
| `res/values/colors.xml` | Color definitions |
| `res/values/themes.xml` | `Theme.Chillpill` (main) and `Theme.Chillpill.Block` (block screen) |
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
