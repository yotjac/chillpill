# Target Android 16 (API 36) for Play compliance

Status: PARTIALLY IMPLEMENTED (T1–T5) — 2026-09-18. T6–T8 are open: they need the Android SDK,
JDK 17 and a device. Written in response to the Play Console warning
"App must target Android 16 (API level 36) or higher", enforced **Aug 31, 2027** (the
console shows the app's highest non-compliant target as API 35). Nothing in this spec has
been built or run: this session has no Android SDK and no JDK 17, so every build and device
step is left for the human.

## Proposal

### Intent
Make Chillpill releasable on Google Play again by moving `targetSdk` from 35 to 36, with the
smallest change that is actually correct — not a general dependency modernisation. Two things
have to happen together:

1. **Toolchain.** `compileSdk = 36` is not accepted by AGP 8.5.2 (max API 35 up to and
   including AGP 8.9). The minimum AGP that compiles against API 36 is **8.10**; AGP **8.11**
   is the version this spec targets, which in turn requires **Gradle 8.13** and **JDK 17**.
2. **Behaviour changes.** Apps targeting 36 get Android 16's opt-in behaviour changes whether
   they want them or not. Section "Behaviour-change audit" below walks every one of them
   against this codebase; exactly one needs a change.

### Non-goals
- No Kotlin upgrade. Kotlin stays **1.9.22**, Compose compiler extension stays **1.5.8**,
  Compose BOM stays **2024.02.00**, KSP stays **1.9.22-1.0.17**, Room stays 2.6.1. Moving to
  Kotlin 2.x means swapping `composeOptions.kotlinCompilerExtensionVersion` for the
  `org.jetbrains.kotlin.plugin.compose` plugin and re-verifying every Composable; that is a
  separate change, and the Play deadline does not require it.
- No AGP 9.x / Gradle 9.x. AGP 9 carries its own breaking changes on top of the API 36 work.
- No androidx dependency bumps. Nothing in this app needs an API 36-aware androidx artifact:
  insets are handled through Compose `foundation`, and `androidx.activity` 1.8.2 already
  registers an `OnBackInvokedCallback`. Bumping `core-ktx` / `activity-compose` would risk
  dragging in artifacts with Kotlin 2.x metadata that 1.9.22 cannot read.
- No adaptive / landscape layouts. Deliberately deferred — see D3.
- No `versionCode` / `versionName` change. The human handles versioning at release time.

### Product decisions (confirmed 2026-09-18)
- **Minimal toolchain bump**, not modernisation (see Non-goals).
- **Opt out of the large-screen orientation change** for now rather than making the screens
  adaptive.
- **Versioning is the human's**; this change leaves `versionCode 5` / `versionName 1.5`.

## Design

### D1. Toolchain versions

| Piece | From | To | Why |
|---|---|---|---|
| Gradle wrapper | 8.7 | **8.13** | Minimum for AGP 8.11 |
| AGP (root `build.gradle.kts`) | 8.5.2 | **8.11.1** | First AGP line that compiles against API 36 is 8.10; 8.11 is the last 8.x that is still plain "API 36" (8.13 goes to 36.1) |
| `compileSdk` | 35 | **36** | Required to compile against Android 16 APIs |
| `targetSdk` | 35 | **36** | The actual Play requirement |
| JDK | — | **17** (unchanged requirement, now enforced) | AGP 8.11 minimum; the project already sets `sourceCompatibility`/`jvmTarget` 17 |
| Kotlin / KSP / Compose | 1.9.22 / 1.9.22-1.0.17 / 1.5.8 | unchanged | See Non-goals |

`minSdk` stays **24**.

**Known risk (R1).** AGP enforces a minimum Kotlin Gradle plugin version, and Google's
Kotlin-support table lists Kotlin 1.9 against AGP 7.4.2–8.2 — a *tested* range, not a hard
bound (the project already runs AGP 8.5.2 on Kotlin 1.9.22 today). AGP 8.11 is expected to
accept KGP 1.9.22, but this cannot be verified without running Gradle. **If the build fails
with a "requires Kotlin Gradle plugin version …" error, the fallback is the Kotlin 2.x path
we deliberately deferred** — Kotlin 2.0.21+, drop `composeOptions`, add
`id("org.jetbrains.kotlin.plugin.compose")`, matching KSP, and a Compose BOM from the same
era. That is a new spec, not an ad-hoc patch.

### D2. Behaviour-change audit (apps targeting API 36)

Every targeted-at-36 behaviour change from
`developer.android.com/about/versions/16/behavior-changes-16`, against this codebase:

| Change | Applies? | Action |
|---|---|---|
| **Edge-to-edge enforced**, `windowOptOutEdgeToEdgeEnforcement` disabled | Already handled | The app targets 35 today, so enforcement is already live. `MainActivity`, `AppBlockActivity` and `ReInterventionActivity` all call `WindowCompat.setDecorFitsSystemWindows(window, false)`; `MainActivity` and `SettingsConfirmationScreen` pad with `WindowInsets.safeDrawing`; `GraceWarningOverlayManager` reads `statusBars()` insets itself. The opt-out attribute is not used anywhere. **No change.** |
| **Predictive back on by default**; `onBackPressed()` and `KEYCODE_BACK` no longer called | Already handled | No `onBackPressed` override and no `KEYCODE_BACK` handling exists. Back is handled through Compose `BackHandler` (`AppBlockActivity`, `ReInterventionActivity`), i.e. `OnBackPressedDispatcher`, which `androidx.activity` 1.8.2 already bridges to `OnBackInvokedCallback`. **No change**, and specifically *do not* add `android:enableOnBackInvokedCallback="false"` — that would be a regression. |
| **Orientation / resizability / aspect-ratio attributes ignored on displays ≥600dp** | **Applies** | See D3. |
| `elegantTextHeight` ignored | No | Attribute unused. |
| `scheduleAtFixedRate` runs at most one missed task | No | No `ScheduledExecutorService`; timers are coroutine-based. |
| Health permissions replace `BODY_SENSORS` | No | No sensor use. |
| Bluetooth bond-loss / encryption intents, `removeBond` | No | No Bluetooth. |
| `MediaStore#getVersion()` now per-app and opaque | No | MediaStore unused. |
| Safer intents (`intentMatchingFlags`) | No — opt-in | Not enabled. Worth considering later, but it is opt-in and the app's exported surface is just the two launcher/settings activities. |
| Local network permission (`NEARBY_WIFI_DEVICES`) | No | The app has no network layer at all. |
| Photo picker pre-selection | No | No photo picker. |
| GPU syscall filtering | No | No native code. |

Two things worth recording that are *not* new at 36 but surface during this change:

- `Theme.kt` sets `window.statusBarColor`, deprecated and a no-op since API 35. It is
  **kept on purpose**: `minSdk` is 24 and it still colours the status bar on Android 14 and
  below. Expect a deprecation warning at `compileSdk 36`; the build does not treat warnings
  as errors.
- `res/values/themes.xml` sets `android:statusBarColor` / `android:navigationBarColor`, also
  no-ops from 35 up and kept for the same reason.

### D3. Large-screen orientation: opt out, for now

All four activities declare `android:screenOrientation="portrait"`. On Android 16, that
attribute (and `resizableActivity`, `minAspectRatio`, `maxAspectRatio`, and
`setRequestedOrientation()`) is ignored on displays whose smallest width is ≥600dp — tablets,
unfolded foldables, desktop windowing. Phones are unaffected.

For Chillpill this is not cosmetic. The block and re-intervention screens are the product:
a stretched or mid-rotation block screen is a block screen the user can get past, and an
orientation change recreates the Activity, which is exactly the moment `BlockingSharedState`
and the wait timer are most fragile.

**Decision:** declare the documented temporary opt-out at application level in the manifest:

```xml
<property
    android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY"
    android:value="true" />
```

This keeps portrait honoured on large screens. It is explicitly temporary — Google's opt-out
stops working once the app targets **API 37**, so this buys roughly one release cycle. The
adaptive-layout work (responsive block screen, state preservation across recreation, tablet
pass) needs its own spec with a visual demo, per the UI rule in `AGENTS.md`, and should start
well before the next target bump.

### D4. Documentation

`ARCHITECTURE.md` §2 is stale — it still claims AGP 8.2.2, `compileSdk 34`, `targetSdk 34`.
It gets corrected to the post-change reality (AGP 8.11.1, Gradle 8.13, compileSdk/targetSdk
36), and §8 (Manifest highlights) gains the new `<property>` with a pointer to D3.

### D5. Verification

This session cannot build: the mounted workspace has JDK 11 and no Android SDK, and
`local.properties` points at the Windows SDK. Everything below is for the human, on Windows,
in order. The first two are the gate — if `assembleRelease` is green the risk in R1 is gone.

1. `gradlew.bat --version` → Gradle 8.13, JVM 17.
2. `gradlew.bat clean assembleDebug` → green (this is where an R1 KGP failure would appear).
3. `gradlew.bat testDebugUnitTest` → `SessionPolicy` tests still pass.
4. `gradlew.bat lintDebug` → no *new* errors; deprecation warnings for `statusBarColor` are
   expected and accepted (D2).
5. `gradlew.bat assembleRelease` (needs `keystore.properties`) → R8/minify still succeeds.
   This is the one that produces the artifact Play will take.
6. Phone, Android 16 if available: install, enable the accessibility service and usage
   access, then walk the core flow — open a restricted app → block screen → wait timer →
   Continue → grace → grace-expiry pill → re-intervention. Confirm the block screen is still
   fullscreen under the cutout and that back from the block screen still goes home.
7. Large screen (tablet or foldable emulator, ≥600dp, Android 16): confirm the app still
   opens portrait and the block screen is not letterboxed or rotated — this is what D3 buys.
8. Android 14 or lower device/emulator: confirm the status bar is still tinted, i.e. the
   kept `statusBarColor` still does its job.

## Tasks

- [x] T1. Gradle wrapper 8.7 → 8.13 in `gradle/wrapper/gradle-wrapper.properties`.
- [x] T2. AGP 8.5.2 → 8.11.1 in the root `build.gradle.kts`.
- [x] T3. `compileSdk = 36` and `targetSdk = 36` in `app/build.gradle.kts`.
- [x] T4. Add the `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` property to `<application>`
      in `app/src/main/AndroidManifest.xml`, with a comment pointing at D3 and the API 37
      expiry.
- [x] T5. Update `ARCHITECTURE.md` §2 (build setup) and §8 (manifest highlights).
- [ ] T6. Human: run the verification list in D5, steps 1–5 (build gate).
- [ ] T7. Human: run the verification list in D5, steps 6–8 (device pass).
- [ ] T8. Follow-up spec `specs/adaptive-large-screen.md` for real large-screen support,
      before the app targets API 37. Not part of this change.
