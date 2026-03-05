# App Block, Accessibility Service, and Grace Period — Spec

This spec describes the blocking flow: AppBlockActivity (block screen UI), ChillpillAccessibilityService (detect app switches and trigger the block), and GracePeriodService (re-intervention when grace period ends). It is the single source of truth for what has been built. It references [chillpill-design.md](chillpill-design.md) and the blocking-activity implementation plan.

---

## Proposal

**Intent**: Implement the full blocking flow so that when a user opens a monitored app outside the grace window, they see a full-screen wait screen; after waiting (or going home), they can continue into the app and receive a time-limited grace period before being blocked again.

**Scope**:
- **AppBlockActivity**: Full-screen block UI with background image and semi-transparent overlay that drains downward as the wait progresses; on completion, show “Opened N times in last 24h” and Continue / Back to Home.
- **ChillpillAccessibilityService**: Listen for `TYPE_WINDOW_STATE_CHANGED`, maintain last-foreground app and grace/closing timestamps; when user opens a monitored app outside grace (and not from Chillpill), record OPEN_ATTEMPT and start AppBlockActivity; when user returns from block, start GracePeriodService.
- **GracePeriodService**: Foreground service that runs for the full grace duration; on expiry, either start AppBlockActivity in-place (if app in foreground) or set a flag so the next open shows the block.
- **Two statistics**: (1) times opened and intercepted (OPEN_ATTEMPT, excludes grace-expired blocks); (2) times waited and continued (WAIT_COMPLETED).
- **Home screen**: `permissionsOk` reflects whether the Chillpill accessibility service is enabled (Settings.Secure).

**Constraints**:
- Block screen is a separate Activity (not in NavHost) so it can appear on top of the monitored app.
- All DataStore/Room access from services runs on a background dispatcher; `startActivity` on main thread.
- Follow [compose-ui SKILL](../.cursor/skills/compose-ui/SKILL.md) (state hoisting, MaterialTheme, ViewModel as single source of truth).

**Out of scope for this spec**: Statistics screen content (uses same repository methods); exact asset for block background (placeholder drawable provided).

---

## Design

### 1. AppBlockActivity and block UI

**Activity**
- Full-screen, `FLAG_KEEP_SCREEN_ON`, no action bar. Theme: `Theme.Chillpill.Block` (fullscreen, no title).
- Intent extras: `packageName` (required), `className` (optional), `isReIntervention` (optional boolean).
- **Back press**: `BackHandler` in Compose starts Home intent (`Intent.ACTION_MAIN` + `CATEGORY_HOME`, `FLAG_ACTIVITY_NEW_TASK`), then `finish()`.
- **onUserLeaveHint**: `finish()`.
- Compose `setContent` with ChillpillTheme and **AppBlockScreen**; ViewModel from custom factory (intent extras + ChillpillApp).

**isReIntervention**
- **Statistics**: OPEN_ATTEMPT is recorded only when the block is shown because the user *opened* the app (AccessibilityService). When the block is shown because grace ended (GracePeriodService or deferred), we do **not** record OPEN_ATTEMPT.
- **UI**: When `isReIntervention == true`, show e.g. “Your grace period is up”; when `false`, show “Opened this app N times in the last 24 hours” (N = intercept count).

**Background**
- Drawable at `res/drawable/block_background` (XML placeholder or PNG). `painterResource(R.drawable.block_background)`, `ContentScale.Crop`, `Modifier.fillMaxSize()`.

**AppBlockViewModel**
- **Inputs**: ChillpillApp, `packageName`, `className`, `isReIntervention`.
- **State**: Phase `WAITING` | `COMPLETED`; progress `0f..1f`; when COMPLETED, `openCount24h: Int`.
- **Logic**: On start, read `waitTimeSeconds` from settings; run timer so progress goes 0→1 over that duration; at 1, set COMPLETED and load `getOpenInterceptCountLast24h(packageName)`.
- **Events**: `onContinue()` — `recordEvent(packageName, WAIT_COMPLETED, sessionStartTime = now)`, emit RequestFinish (activity finishes). `onGoHome()` — emit RequestGoHome (activity starts Home intent and finishes). Events delivered via a Flow so Activity can collect and act.

**AppBlockScreen** (state-hoisted)
- **WAITING**: Full-screen background image; on top a semi-transparent overlay with height `fillMaxHeight(1f - progress)` so it “drains” as progress increases. No “X seconds” text.
- **COMPLETED**: Same background, overlay gone; card with message (opens count or grace-expired), primary “Continue to app”, secondary “Back to Home”. Buttons call `onContinue` and `onGoHome`.
- Root composable: `modifier: Modifier = Modifier`; use MaterialTheme for typography and colors.

### 2. ChillpillAccessibilityService

**Manifest and config**
- Service declared in AndroidManifest: `android:permission="BIND_ACCESSIBILITY_SERVICE"`, `android:exported="false"`, intent-filter for `AccessibilityService`, meta-data `android:resource="@xml/accessibility_service_config"`.
- **accessibility_service_config.xml**: `android:description`, `android:accessibilityEventTypes="typeWindowStateChanged"`, `android:accessibilityFeedbackType="feedbackGeneric"`, `android:canRetrieveWindowContent="false"`, `android:settingsActivity`. In `onServiceConnected`, set `AccessibilityServiceInfo` with `packageNames = null` so the service receives events for all packages; filtering by monitored set is done in code.

**In-memory state**
- `lastPackage`, `lastClassName`: current foreground from last processed event.
- `blockShownAt: MutableMap<String, Long>`: when we start AppBlockActivity for a package, set to now; when we see transition from Chillpill to that package (user tapped Continue), clear.
- `latestClosingTime: MutableMap<String, Long>`: when user leaves a monitored app (new package ≠ lastPackage), set for that package.

**Event handling** (only `TYPE_WINDOW_STATE_CHANGED`)
- Ignore if `event.packageName` or `event.className` is null.
- **Same app**: if `event.packageName == lastPackage`, update lastPackage/lastClassName and lastForegroundPackage, return.
- **Leaving monitored app**: if `lastPackage` is in monitored set and new package ≠ lastPackage, set `latestClosingTime[lastPackage] = now`. Do **not** pause GracePeriodService. Write `lastForegroundPackage` to shared state. Then update lastPackage/lastClassName.
- **Entering monitored app** (event.packageName in monitored set):
  - If **graceExpiredForPackage == package**: show block with `isReIntervention = true`, clear flag; do **not** record OPEN_ATTEMPT.
  - Else if **lastPackage == Chillpill**: user returned from block; clear `blockShownAt[package]`, start GracePeriodService (START/RESUME); do not show block.
  - Else (async): read settings and check (a) grace expired: `latestClosingTime[package] + graceMinutes*60*1000 < now` (or package not in map); (b) previous app ≠ Chillpill. If (a) and (b): record OPEN_ATTEMPT, set blockShownAt[package], start AppBlockActivity with `isReIntervention = false`. If not (a): start/resume GracePeriodService.
- After all logic, set lastPackage/lastClassName and write **lastForegroundPackage** to shared state.

**Repositories**
- `applicationContext as ChillpillApp` for settingsRepository, monitoredAppsRepository, usageEventsRepository. All repository/DataStore reads on Dispatchers.IO; startActivity on main.

### 3. GracePeriodService

**Role**
- Foreground service that runs for the **full grace duration** regardless of whether the user stays in the monitored app. No PAUSE when user leaves. On expiry: if monitored app is in foreground, start AppBlockActivity with `isReIntervention = true`; else set shared **graceExpiredForPackage** so AccessibilityService shows the block on next open.

**Manifest**
- Declare service; `android:foregroundServiceType="specialUse"`; `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="..."/>`. Permission `FOREGROUND_SERVICE_SPECIAL_USE`.

**API**
- **START / RESUME**: Intent action and extras `packageName`, `className`. Create notification channel, `startForeground` with low-priority notification (“Chillpill – grace period”). Read `gracePeriodMinutes` from SettingsRepository once; run timer (e.g. tick every second). When `elapsed >= gracePeriodMinutes * 60`: read **lastForegroundPackage** from shared state. If it equals our packageName, start AppBlockActivity (same flags, `isReIntervention = true`); else set **graceExpiredForPackage = packageName**. Then `stopSelf()`.

### 4. Shared state

**BlockingSharedState** (object)
- `lastForegroundPackage: String?` — written by AccessibilityService on every relevant transition; read by GracePeriodService on expiry.
- `graceExpiredForPackage: String?` — set by GracePeriodService when grace expires and app not in foreground; read and cleared by AccessibilityService when user next opens that app.

### 5. Data and repositories

- **OPEN_ATTEMPT**: Recorded only when AccessibilityService starts the block (user opened app outside grace). Not recorded when block is shown due to grace expiry.
- **WAIT_COMPLETED**: Recorded in AppBlockViewModel when user taps “Continue to app” (with `sessionStartTime`).
- **UsageEventsRepository**: `getOpenInterceptCountLast24h(packageName)` (count OPEN_ATTEMPT in last 24h), `getWaitCompletedCountLast24h(packageName)` (count WAIT_COMPLETED in last 24h). Block screen “Opened N times” uses intercept count.

### 6. Wiring

- **AppBlockActivity**: Started with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_NO_HISTORY`. Declared with `exported="false"`.
- **Strings**: e.g. “Opened this app %d times in the last 24 hours”, “Your grace period is up”, “Continue to app”, “Back to Home”, “Chillpill – grace period”, accessibility service description.
- **HomeViewModel**: `permissionsOk` set from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (component name of ChillpillAccessibilityService). HomeScreen uses ViewModel factory that takes ChillpillApp; `refreshPermissions()` on init and when screen is displayed (LaunchedEffect(Unit)).

### 7. File and package layout

| Item                          | Location                                                |
| ----------------------------- | ------------------------------------------------------- |
| AppBlockActivity              | `app/.../AppBlockActivity.kt`                           |
| AppBlockScreen, AppBlockViewModel | `app/.../ui/appblock/AppBlockScreen.kt`, `AppBlockViewModel.kt` |
| ChillpillAccessibilityService | `app/.../service/ChillpillAccessibilityService.kt`      |
| GracePeriodService            | `app/.../service/GracePeriodService.kt`                  |
| BlockingSharedState           | `app/.../service/BlockingSharedState.kt`                |
| accessibility_service_config.xml | `res/xml/accessibility_service_config.xml`            |
| block_background              | `res/drawable/block_background.xml` (or .png)           |

---

## Tasks

- [x] **Block background and shared state**: Placeholder drawable `block_background.xml`; `BlockingSharedState` with lastForegroundPackage and graceExpiredForPackage.
- [x] **AppBlockViewModel**: Phase, progress, openCount24h; timer over waitTimeSeconds; onContinue (record WAIT_COMPLETED, emit RequestFinish); onGoHome (emit RequestGoHome); events as Flow.
- [x] **AppBlockScreen**: WAITING (background + overlay height by progress); COMPLETED (card with message, Continue, Back to Home); state-hoisted; MaterialTheme.
- [x] **AppBlockActivity**: Full-screen, FLAG_KEEP_SCREEN_ON; read extras; custom ViewModel factory; BackHandler → Home + finish; onUserLeaveHint → finish; collect ViewModel events and start Home or finish.
- [x] **ChillpillAccessibilityService**: Manifest + accessibility_service_config.xml (accessibilityEventTypes, accessibilityFeedbackType); onServiceConnected set packageNames = null; in-memory lastPackage, lastClassName, blockShownAt, latestClosingTime; onAccessibilityEvent → processEvent (same app, leaving monitored, entering monitored, graceExpiredForPackage, lastPackage == Chillpill, grace check); start AppBlockActivity or GracePeriodService; write lastForegroundPackage.
- [x] **GracePeriodService**: Manifest (foregroundServiceType specialUse, property, permission); START/RESUME with packageName/className; foreground notification; timer for full grace duration; on expiry read lastForegroundPackage, start activity or set graceExpiredForPackage; stopSelf().
- [x] **Usage events**: OPEN_ATTEMPT only from AccessibilityService block path; getOpenInterceptCountLast24h, getWaitCompletedCountLast24h in repository (DAO countEventsSince).
- [x] **Wiring**: Manifest (activity, both services, permissions); themes (Theme.Chillpill.Block); strings; HomeViewModel permissionsOk from Settings.Secure, factory in HomeScreen, refreshPermissions on display.

---

## Summary

| Area        | Content                                                                 |
| ----------- | ----------------------------------------------------------------------- |
| AppBlock    | Full-screen Activity; background + draining overlay; COMPLETED card with N opens, Continue / Back to Home; ViewModel with timer and events |
| Accessibility | TYPE_WINDOW_STATE_CHANGED; same app / leaving / entering; OPEN_ATTEMPT only when block from open; graceExpiredForPackage and lastPackage==Chillpill handled; start Block or Grace service |
| GracePeriod | Foreground service, full grace duration, no pause; on expiry either start Block in-place or set graceExpiredForPackage |
| Statistics  | OPEN_ATTEMPT = intercepted opens (excl. grace-expired); WAIT_COMPLETED = waited and continued; repository methods for 24h counts |
| Home        | permissionsOk from Settings.Secure (accessibility service enabled); refresh when screen shown |

This spec reflects the implemented behavior. For new changes, update this spec first, then code.
