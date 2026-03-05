# Monitored Apps Selection — Spec

This spec describes how the Settings screen builds and displays the list of apps that can be selected for monitoring. It ensures the picker shows only user-relevant (launcher) apps, excludes internal/system components, and preserves existing selections. It references [data-model-main-settings.md](data-model-main-settings.md) and [app-block.md](app-block.md).

---

## Proposal

**Intent**: The monitored-apps list in Settings should show apps that the user can launch from the app drawer (e.g. YouTube, Instagram, Chrome), including those installed as system apps, and must not show internal components (e.g. SystemUI, SIM Toolkit, com.googleSSRestartDetector). Already-selected packages must remain visible even if they no longer appear in the launcher (e.g. disabled app).

**Scope**:
- Build the list from launcher-intent query instead of all installed applications.
- Declare package visibility via `<queries>` so Android 11+ returns all launcher apps.
- Merge in the current monitored set so existing selections stay in the list.
- Load the list on a background dispatcher to avoid blocking the UI.

**Constraints**:
- Do not filter by “user vs system” install; launcher presence is the only inclusion criterion (so system-installed YouTube/Instagram appear).
- Chillpill itself is always excluded from the list.

---

## Design

### 1. Package visibility (Android 11+)

**File**: [app/src/main/AndroidManifest.xml](../app/src/main/AndroidManifest.xml)

- Inside `<manifest>`, before `<application>`, declare:
  - `<queries>` with one `<intent>`: `ACTION_MAIN` and `CATEGORY_LAUNCHER`.
- This grants the app visibility to all packages that resolve for the launcher intent, so they can appear in the monitored-apps list on Android 11+.

### 2. Building the app list

**File**: [app/src/main/java/com/chillpill/ui/settings/SettingsViewModel.kt](../app/src/main/java/com/chillpill/ui/settings/SettingsViewModel.kt)

- **Entry point**: `loadInstalledApps()` (called from init after loading settings and monitored set).
- **Thread**: Run the PackageManager work on `Dispatchers.IO`; set `_installedApps` on the main thread after the result is ready.

**Steps**:
1. Read the current monitored set from `MonitoredAppsRepository.monitoredPackages.first()`.
2. Call `PackageManager.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0)` to get all activities that appear in the launcher.
3. From the result, collect distinct package names, excluding the app’s own package (Chillpill).
4. Union that set with the monitored set so any already-selected package is included even if it is not in the launcher result.
5. For each package in the combined set, resolve an application label via `getApplicationInfo(packageName, 0)` and `getApplicationLabel(applicationInfo)`; on `NameNotFoundException`, skip that package (or use package name as fallback).
6. Sort by label (case-insensitive) and assign to `_installedApps`.

**API**: Use the `queryIntentActivities(Intent, int)` overload with `0` for compatibility with minSdk 24; suppress deprecation if needed on API 33+.

### 3. Resulting behavior

| Included | Excluded |
| -------- | -------- |
| Apps with a launcher activity (YouTube, Instagram, Chrome, etc.), whether user- or system-installed | Chillpill (own app) |
| Any package already in the user’s monitored set | System/internal components with no launcher (SystemUI, SIM Toolkit, com.googleSSRestartDetector, etc.) |

### 4. UI

- No change to [SettingsScreen.kt](../app/src/main/java/com/chillpill/ui/settings/SettingsScreen.kt): it continues to consume `installedApps` and apply the search filter as before.

---

## Summary

| Item | Description |
| ---- | ----------- |
| **Manifest** | `<queries>` with MAIN + LAUNCHER intent for package visibility on Android 11+. |
| **Source of list** | `queryIntentActivities(ACTION_MAIN, CATEGORY_LAUNCHER)` (launcher-only). |
| **Merge** | Union with current monitored set so existing selections remain visible. |
| **Threading** | PackageManager work on `Dispatchers.IO`. |
| **Exclusions** | Chillpill; packages that throw `NameNotFoundException` when resolving label are skipped. |
