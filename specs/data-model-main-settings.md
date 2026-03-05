# Data Model, Main Menu, and Settings Screen — Spec

This spec describes the first implementation slice of Chillpill: the data model, main activity with Home screen, and Settings screen. It is the single source of truth for what has been built and can be used for traceability and future changes. It references [chillpill-design.md](chillpill-design.md) and was derived from the first implementation plan and the data/main/settings plan.

---

## Proposal

**Intent**: Implement the core data layer and the two primary user-facing screens (Home and Settings) so that users can configure wait time, grace period, and the set of monitored apps. No blocking, accessibility service, or statistics yet.

**Scope**:
- Data model: Settings (wait time, grace period), monitored app package names, usage events (Room table for future use).
- Main activity: Single-activity Compose app with NavHost; Home and Settings as destinations; Statistics as a stub.
- Home screen: Title “Chillpill”, Settings and Statistics buttons, permission banner with “fix here” link.
- Settings screen: Configuration inputs (wait time in seconds, grace period in minutes), searchable list of installed apps with checkboxes and app icons; persistence on change (no Save button).
- GitHub: .gitignore, README, LICENSE, repo init.

**Constraints**:
- Single-activity navigation (Settings is a screen, not a separate Activity).
- Persist settings and monitored set as the user changes them; support clearing and re-entering configuration values with validation and defaults.
- Follow [compose-ui SKILL](../.cursor/skills/compose-ui/SKILL.md) (state hoisting, MaterialTheme, ViewModel as single source of truth) and [spec-first](../.cursor/rules/spec-first.md).

**Out of scope for this spec**: AppBlockActivity, AccessibilityService, GracePeriodService, Statistics screen content, real permission detection (banner state can be a placeholder).

---

## Design

### Data model and persistence

| Concept            | Storage               | Shape                                                                 |
| ------------------ | --------------------- | --------------------------------------------------------------------- |
| **Settings**       | DataStore Preferences | `waitTimeSeconds: Int`, `gracePeriodMinutes: Int` (defaults 12, 5)   |
| **Monitored apps** | DataStore             | `Set<String>` (package names); labels from PackageManager in UI      |
| **Usage events**   | Room                  | `packageName`, `timestamp`, `eventType`, optional `sessionStartTime`|

- **SettingsRepository**: `Flow<Settings>`, `setWaitTimeSeconds(Int)`, `setGracePeriodMinutes(Int)`, `setSettings(Settings)`. DataStore with int preference keys.
- **MonitoredAppsRepository**: `Flow<Set<String>>`, `setMonitored(Set<String>)`, `add`/`remove` by package name. DataStore with string set.
- **UsageEventsRepository**: Room-backed; `recordEvent(...)`, `getEventsInLast24h(packageName)`, etc. Entity uses `@ColumnInfo(name = "package_name")` (and similar) so SQL uses snake_case column names.

Settings and monitored set are written when the user changes values (e.g. on focus loss for config fields, on checkbox toggle for apps). Configuration fields: string state so the user can clear and type; valid range wait 1–7200 s, grace 1–1440 min; empty/invalid reset to defaults (12 s, 5 min) on focus loss.

### Main activity and navigation

- **MainActivity**: Compose `setContent { ChillpillTheme { Surface { ChillpillNavHost(...) } } }`. Receives `ChillpillApp` for repository access and `onFixPermissions` (e.g. intent to `Settings.ACTION_ACCESSIBILITY_SETTINGS`).
- **Routes**: `home`, `settings`, `statistics`. NavHost defines enter/exit and pop transitions (e.g. slide) for a consistent feel.
- **ChillpillApp**: Application class; provides `SettingsRepository`, `MonitoredAppsRepository`, `UsageEventsRepository` (Room DB with `fallbackToDestructiveMigration()`). Used by ViewModels via factory.

### Home screen (main menu)

- **Layout**: Full-width permission banner at top when `permissionsOk == false`; message ends with clickable underlined “fix here” (opens system settings). Below: centered title “Chillpill”, then two buttons: Settings, Statistics.
- **State**: `HomeViewModel` exposes `permissionsOk: StateFlow<Boolean>` (placeholder false). Events: `onOpenSettings`, `onOpenStatistics`, `onFixPermissions` (passed from NavHost / Activity).
- **Theme**: Material3; buttons use theme shape (e.g. medium corner radius) and fixed height (e.g. 56.dp). No “Fix Permissions” button; only “fix here” in the banner.

### Settings screen

- **Layout**: App bar with back arrow (regular back icon, no “Back” text) and title “Settings”. Body: **Configuration** section (label “Configuration”) with two number fields: “Wait time (seconds)”, “Grace period (minutes)”. Then **Monitored apps** section: “Search apps” text field; margin below it; scrollable list of installed apps. Each row: checkbox, app icon, app name. Invalid configuration fields show red (e.g. error state) and are corrected on focus loss.
- **Behavior**: Back navigates to Home. Wait time and grace period persisted when valid (and on focus loss after normalizing empty/invalid to defaults). Monitored set persisted when user toggles a checkbox. App icons loaded via `PackageManager.getApplicationIcon(packageName)` with try/catch for `NameNotFoundException` (e.g. show placeholder if package was uninstalled).
- **State**: ViewModel holds string inputs for config (and validated int values for persistence), `installedApps: List<AppInfo>`, `monitoredPackages: Set<String>`, `appSearchQuery`. Events: `onWaitTimeChanged`, `onGracePeriodChanged`, `onMonitoredChanged`, `onSearchQueryChanged`, `onWaitTimeFocusLost`, `onGracePeriodFocusLost`. The app list is built from `PackageManager.queryIntentActivities(ACTION_MAIN, CATEGORY_LAUNCHER)` (launcher-only apps) plus any already-monitored packages, so the picker shows only user-relevant apps and excludes internal components (e.g. SystemUI). Android 11+ package visibility is satisfied via `<queries>` for the launcher intent in the manifest.

### Theme and visuals

- **Theme**: Modern palette (e.g. teal/slate); custom shapes (e.g. 8.dp for medium) for less round buttons. Alert banner: more “alerting” colors (e.g. error/tertiary container with a slight red tint).
- **Visual demos**: Wireframes in `assets/chillpill-home-screen-demo.png` and `assets/chillpill-settings-screen-demo.png` for reference.

### File and module structure

- **Packages**: `ui.home`, `ui.settings`, `ui.statistics`, `ui.navigation`, `ui.theme`; `data.settings`, `data.monitored`, `data.usage`.
- **Key files**: `MainActivity.kt`, `ChillpillApp.kt`, `ui/theme/Theme.kt`, `ui/navigation/NavGraph.kt`, `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `ui/statistics/StatisticsScreen.kt`, `data/settings/Settings.kt`, `data/settings/SettingsRepository.kt`, `data/monitored/MonitoredAppsRepository.kt`, `data/usage/UsageEvent.kt`, `UsageEventsDao.kt`, `UsageDatabase.kt`, `UsageEventsRepository.kt`, `res/values/themes.xml`, `res/values/strings.xml`.

### GitHub

- `.gitignore` (Android/Gradle/Kotlin), `README.md` (project name, description, link to design doc, build/run), `LICENSE` (e.g. MIT). Repo initialized with `git init`.

---

## Tasks

- [ ] **Bootstrap**: Root and app `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, Gradle wrapper; `AndroidManifest.xml` with `ChillpillApp` and `MainActivity` as launcher; Compose, Material3, Navigation, DataStore, Room, KSP.
- [ ] **Theme**: `Theme.kt` (light/dark color scheme, custom shapes), `Type.kt`; apply in MainActivity.
- [ ] **Data — Settings**: `Settings` data class; DataStore-backed `SettingsRepository` (Flow + set methods).
- [ ] **Data — Monitored apps**: DataStore-backed `MonitoredAppsRepository` (Flow<Set<String>>, setMonitored, add/remove).
- [ ] **Data — Usage**: Room entity (`UsageEvent` with `@ColumnInfo` for snake_case columns), DAO (queries using `package_name`, `event_type`), Database, `UsageEventsRepository`; provide from ChillpillApp.
- [ ] **Navigation**: `Routes`, `ChillpillNavHost` with slide transitions; composable destinations for home, settings, statistics.
- [ ] **Home screen**: `HomeViewModel` (permissionsOk stub), `HomeScreen` (banner with “fix here”, title, Settings and Statistics buttons); wire in NavHost; pass onFixPermissions from MainActivity.
- [ ] **Settings screen**: `SettingsViewModel` (config string state, validation, defaults on focus loss; installed apps from launcher-intent query + already-monitored set; monitored set; persist on change); `SettingsScreen` (Configuration section with two number fields and error state when invalid; Monitored apps with search field, margin, list with checkbox + icon + label; AppIcon with NameNotFoundException handling); back arrow only (no “Back” text).
- [ ] **Statistics stub**: `StatisticsScreen` (title “Statistics”, “Coming soon”, back only).
- [ ] **GitHub**: `.gitignore`, `README.md`, `LICENSE`; run `git init` (or document in README).

---

## Summary

| Area        | Content                                                                 |
| ----------- | ----------------------------------------------------------------------- |
| Data        | Settings + MonitoredApps + UsageEvents (DataStore + Room); repositories from ChillpillApp |
| Main        | Single Activity, NavHost, Home + Settings + Statistics (stub)          |
| Home        | Banner (“fix here”), title, Settings and Statistics buttons             |
| Settings    | Configuration (wait, grace) with validation and red when invalid; monitored app list with search, icons, checkboxes; persist on change |
| Conventions | Compose UI skill (state hoisting, MaterialTheme); spec-first; visual demos in assets |

This spec reflects the implemented behavior. For new changes, update this spec first, then code.
