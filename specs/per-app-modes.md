# Per-app modes ("how Chillpill steps in", per restricted app)

Status: **IMPLEMENTED** (2026-11-13), pending Gradle build/lint and the device checklist (tasks
17–18). Demo: `specs/demos/per-app-modes.html`. See "Implementation notes" at the end.

Builds on `specs/session-engine.md` (all blocking logic stays in `EngineCore`) and
`specs/no-reblock-apps-session-fix.md` (the 10 s return window of no-re-block apps).

## Proposal

### Problem

Today every restricted app shares one wait time and one grace period, and the only per-app
choice is a toggle with an engine-ish name, **"Block again after grace"**. Users can't say
"Instagram: let me pick how long, YouTube: just slow me down". The re-intervention screen also
reuses the opening wait, so "a short pause at the door, a long one when time's up" can't be
set. The wait before saving looser settings is tied to the opening wait too.

### Intent

1. Each restricted app has its own **mode** plus its own numbers. There are three modes:
   - **Pause only**: wait when opening, then use the app freely. During the *free re-entry*
     window, coming back skips the wait, and nothing closes the app. This is today's
     "Block again after grace" **off**.
   - **Time limit**: wait when opening, get a fixed amount of time, and be stopped when it
     runs out. This is today's "Block again after grace" **on**.
   - **Pick your time** (new): wait when opening (this can be 0), then choose how long this
     visit lasts on a slider. When it runs out the user waits again and can pick another
     round, or go home.
2. **Independent waits per app:** *wait when opening* and *wait when time's up* (the
   re-intervention screen, which in Pick mode comes before picking again).
3. **Confirmation wait** for saving looser settings is its own global setting (default 10 s).
   Lowering it is itself a loosening, so it requires the current wait.
4. ~~**Defaults + overrides:**~~ *(dropped in round 2, see D7)* a "Defaults" profile that new apps get and that apps follow until
   the user customises them.

### Naming (user-facing)

The underlying idea is a *stopping policy*. In the UI it is the app's **mode**, shown under the
heading **"When I open {App}"**:

| Mode | Label | One-line description |
|------|-------|----------------------|
| PAUSE_ONLY | **Pause only** | Wait, then use it freely |
| TIME_LIMIT | **Time limit** | Wait, then get {N} min, then it closes |
| PICK | **Pick your time** | Wait, then choose how long each visit lasts |

(Round 1: "Speed bump" rejected. Other candidates for this label: *Just a pause*, *Wait, then
free*, *Breather*. Labels are strings only; the code name is `PAUSE_ONLY`.)

Other renames in the same pass:
- "Wait time" → **Wait when opening** and **Wait when time's up**
- "Grace period" → **Time allowed** (Time limit) / **Free re-entry for** (Pause only)
- "Block again after grace" → removed (replaced by the mode).

### Decisions (Yotam, 2026-11-13)

- **D1 Labels:** Time limit and Pick your time stay; "Speed bump" is replaced (now "Pause only",
  pending final word).
- **D2 Picker:** a **slider, 1–30 min**, with quick-set buttons **5 / 10 / 15 / 20 / 25 / 30**.
  A **"Need longer?"** link switches the slider to **1–90 min** (quick buttons become
  15 / 30 / 45 / 60 / 75 / 90). There is no settings-level configuration of the choices.
- **D3 Time's up in Pick mode:** after the app's *wait when time's up*, the picker shows again
  (another round) next to **Go home**. That wait is set per app, independently of the opening
  wait.
- **D4 Confirmation wait:** a global setting, **default 10 s**. Lowering it counts as reducing
  restrictions (it needs the current confirmation wait). No floor.
- **D5 Order vs. study mode:** study mode is ignored for now; this lands on `main`.
- **D7 No defaults section (round 2):** Settings has no Defaults editor. Every app silently starts
  with the fixed `AppPolicy.DEFAULT` = **Time limit, 10 s opening wait, 10 s time's-up wait,
  5 min, "+10 s" on**, and is changed on its own card. Only policies that differ from it are
  stored. Upgrading installs keep their old per-app behaviour (see Persistence).
- **D8 Naming (round 2):** the confirmation-wait section is called **"Loosening your limits"**
  (was "Safety"); the "0 shows the picker right away" hint is removed.
- **D6 Zero waits:** only Pick your time may have a 0-s opening wait. Pause only and Time limit
  have a minimum of 1 s (as today). The times-up wait has a minimum of 1 s in every mode.

### Non-goals

- Schedules (for example a different mode at night) and daily budgets across visits.
- Per-app waiting-screen images (background stays global).
- New statistics (for example "average chosen time"). The Room schema stays unchanged.
- Changing presence / probe logic. None of P1–P6b is touched.

## Design

### Model (pure, `service/engine/EngineModel.kt`)

```kotlin
enum class Mode { PAUSE_ONLY, TIME_LIMIT, PICK }

/** What the engine needs per restricted app. Waits are UI-only and not in here (see below). */
data class AppRule(
    val mode: Mode,
    /** PAUSE_ONLY: free re-entry window. TIME_LIMIT: time allowed. PICK: unused. */
    val graceMs: Long,
    /** "+10 s once" on the first warning pill (TIME_LIMIT / PICK). */
    val extensionAllowed: Boolean = true
)

data class Config(
    /** Effective rule per restricted package (overrides already resolved against defaults). */
    val rules: Map<String, AppRule>,
    /** Applied to apps restricted from the suggestion popup until their DataStore write lands. */
    val defaultRule: AppRule
) { val restricted: Set<String> get() = rules.keys }
```

- New constants in `EngineCore`: `PICK_MIN_MS = 1 min`, `PICK_MAX_MS = 90 min`,
  `PICK_FALLBACK_MS = 5 min`.
- `Session` gains `rule: AppRule`, a snapshot taken when the session starts. A settings change
  therefore applies from the **next** session, and a running session never changes mode under
  the user. A7 still drops sessions of apps that are no longer restricted.
- `Input.ContinueTapped` gains `chosenMs: Long? = null`. The default keeps old traces decodable.

### Engine rules (`EngineCore`) and what changes

| Rule | Today | New |
|------|-------|-----|
| `isNoReblock(pkg)` | `pkg in config.noReblock` | `session.rule.mode == PAUSE_ONLY` (or the config rule when there is no session yet) |
| A2/A3 Continue | `deadline = at + config.graceMs` | TIME_LIMIT / PAUSE_ONLY: `at + rule.graceMs`. PICK: `at + chosenMs`, rounded to whole minutes and clamped to 1–90 min. Missing `chosenMs` → 5 min. The engine never trusts the UI beyond 90 min. |
| A5 Extend | always allowed once | also requires `rule.extensionAllowed` |
| A8 suggestion RESTRICT | `config.graceMs` | `defaultRule`: PAUSE_ONLY / TIME_LIMIT use `graceMs`; PICK uses `PICK_FALLBACK_MS` (the user never picked) |
| G1/G2/G3 expiry | by `isNoReblock` | unchanged, reading the session's rule. PICK expires exactly like TIME_LIMIT (G2 → `RE_INTERVENTION`) |
| `warning()` | null for no-re-block | null for PAUSE_ONLY; `canExtend` also requires `extensionAllowed` |
| E7 return window | no-re-block apps | PAUSE_ONLY apps (same 10 s constant) |

The block kinds are unchanged: `RE_INTERVENTION` in PICK mode simply leads to the picker after
its wait. A Continue from a `RE_INTERVENTION` block already starts a fresh `Session`, so
"another round" needs no new state. The invariants I1–I4 hold as they are. The fuzz test gains
random modes and random `chosenMs` values (including out-of-range ones).

### Waits live in the UI, not the engine

The engine never knew the wait time, and that stays true: `AppBlockViewModel` resolves the
effective policy for its package from the repositories and uses `openWaitSeconds` for `INITIAL`
and `timesUpWaitSeconds` for `RE_INTERVENTION`. A settings race between the engine's decision
and the VM's read is harmless, because the engine clamps the chosen duration.

### Persistence

The stored shape (UI and repo side) carries the waits too:

```kotlin
data class AppPolicy(               // data/restricted/AppPolicy.kt
    val mode: Mode,
    val openWaitSeconds: Int,       // PICK: 0..7200; others: 1..7200
    val timesUpWaitSeconds: Int,    // 1..7200, used by TIME_LIMIT / PICK
    val minutes: Int,               // 1..1440: free re-entry (PAUSE_ONLY) or time allowed (TIME_LIMIT)
    val extensionAllowed: Boolean
) { fun toRule(): AppRule }
```

- **Default (D7):** not stored: `AppPolicy.DEFAULT` (Time limit, 10 s / 10 s, 5 min).
- **Confirmation wait:** `settings` DataStore, new key `confirmation_wait_seconds` (1..7200).
- **Overrides:** `restricted_apps` DataStore, new key `policy_overrides` (a JSON map from
  package to `AppPolicy`). It lives next to `package_names`, so `RestrictedAppsSnapshot` stays a
  single consistent read. A package without an entry gets `AppPolicy.DEFAULT`; entries equal to
  it are dropped (JSON via the Gson that is already a dependency).
- **Legacy fallback (pure `PolicyResolver`, no migration write):**
  - When `policy_overrides` is absent and any legacy key exists, every restricted app keeps
    what it had: `TIME_LIMIT(openWait = timesUpWait = wait_time_seconds ?: 12,
    minutes = grace_period_minutes ?: 5)`, as `PAUSE_ONLY` if in `re_intervention_disabled`.
    (An old install that never saved settings has no legacy key and gets the new default: its
    wait goes 12 s → 10 s.)
  - When `confirmation_wait_seconds` is absent:
    - on an existing install it is `wait_time_seconds`, so upgrading never shortens the wait
      someone already had;
    - on a fresh install it is 10 s.

  The first Save writes the new keys. The legacy keys stay one release, then are deleted in a
  follow-up.
- If a stored or legacy policy has mode ≠ PICK and `openWaitSeconds == 0`, it is coerced to 1
  (D6).
- `SessionEngine.configFlow()` maps the resolved policies to `Config(rules, defaultRule)`.
- `EngineTrace` decoding converts old-shape `ConfigChanged` (`restricted`, `noReblock`,
  `graceMs`) into rules (TIME_LIMIT / PAUSE_ONLY). `TraceReplayTest` must stay green unchanged.

### Block screens (`ui/appblock`, `ui/reintervention`)

- The wait timer accepts 0 (PICK only). When the wait is 0, it goes straight to the next phase,
  so there is no flash of the progress fill.
- New phase `PICKING` (PICK mode) replaces `COMPLETED`. The picker contains:
  - The title *"How long do you need?"*.
  - A big live value ("10 min").
  - A **slider 1–30 min** in 1-min steps, starting at 5 min.
  - A row of quick buttons **5 · 10 · 15 · 20 · 25 · 30**. A quick button only moves the slider;
    it does not open the app.
  - A **"Need longer?"** text button that switches to **1–90 min** (quick buttons 15 · 30 · 45 ·
    60 · 75 · 90). The range resets to 1–30 on every new block screen.
  - The primary button **"Open for 10 min"**, which sends `ContinueTapped(pkg, chosenMs)`, and
    the existing **Go home**.
- Time's up (re-intervention) in PICK mode: the times-up fill runs, then the screen shows
  *"Time's up. Take another round?"* with the same picker and **Go home** (D3).
- The "opened N times in 24 h" line is unchanged.

### Settings (`ui/settings`)

- The **Configuration** section is removed (D7). Each restricted app card contains a reusable
  `PolicyEditor` composable:
  1. **"When I open {App}"**: three selectable rows (mode name + one-line description).
  2. Mode-specific fields:
     - **Pause only:**
       - *Wait when opening* (1–7200 s)
       - *Free re-entry for* (min)
     - **Time limit:**
       - *Wait when opening* (1–7200 s)
       - *Time allowed* (min)
       - *Wait when time's up* (s)
       - *Allow "+10 s" once* (switch)
     - **Pick your time:**
       - *Wait when opening* (0–7200 s)
       - *Wait before picking again* (s); this is the times-up wait, worded for this mode
       - *Allow "+10 s" once* (switch)

  Switching a mode to Pause only / Time limit while the opening wait is 0 sets it to 1 and
  marks the field pending.
- A **"Loosening your limits"** section holds **"Wait before saving looser settings"**
  (seconds, default 10).
- **Restricted app card:**
  - Collapsed, it shows a summary, e.g. `Time limit · 12 s · 5 min`, `Pick · 0 s`, or
    `Pause only · 20 s · 15 min re-entry`.
  - Expanded, it shows the app's `PolicyEditor` (its saved policy, or the default).
- New apps (picker or suggestion popup) get `AppPolicy.DEFAULT`. Pending-change highlighting works as
  today, per field.
- **Restriction-reducing check** uses a pure, unit-tested `isPolicyLooser(old, new)` on the
  *effective* policy of every app that was restricted before. It also counts as reducing when
  a restricted app is removed, or the confirmation wait goes down. A
  policy is looser when any of these holds:
  - opening wait ↓
  - times-up wait ↓ (TIME_LIMIT / PICK)
  - PAUSE_ONLY free re-entry ↑
  - TIME_LIMIT time ↑
  - extension off → on
  - TIME_LIMIT / PICK → PAUSE_ONLY
  - TIME_LIMIT → PICK when the old time < 90 min (Pick can reach 90)
  - PICK → TIME_LIMIT is looser only when the new time > 90 min

  Moving to a stricter mode is never reducing.
- The confirmation screen waits the **persisted** confirmation wait (a draft lowering it does
  not apply to its own Save).

### Risks

- **Settings screen density.** Mitigated by collapsed cards with a summary line,
  by showing only the mode's own fields, and by Pick having no choice editor any more.
- **Session rule snapshot vs. legacy `noReblock` logic.** Every `isNoReblock` call site must
  read the session's rule when one exists. The fuzz test changes rules mid-session to catch a
  miss.
- **Slider precision on small screens.** A 1–90 slider in 1-min steps is fiddly. The quick
  buttons cover the common values, and the slider supports fine-tuning with haptic ticks.

## Tasks

- [ ] 0. Commit the pending launcher-blip work on `main`. Decide D5 (this before or after
      `study-mode`).
- [x] 1. Mark spec ACCEPTED ("Pause only" kept as the label for now; strings only if it changes).
- [x] 2. `EngineModel`: `Mode`, `AppRule`, new `Config`, `Session.rule`, `ContinueTapped.chosenMs`.
- [x] 3. `EngineCore`: rules table above (A2/A3 clamp, A5, A8, G1–G3, E7, `warning()`).
- [x] 4. `EngineHarness` / `EngineCoreTest`:
  - port existing tests to the new `Config` (behaviour-preserving)
  - PICK clamp (0, 91 min, missing)
  - re-intervention → new round
  - extension off
  - mode change mid-session applies next session
  - suggestion RESTRICT with PICK defaults
- [x] 5. `EngineFuzzTest`: random modes / `chosenMs`, rule changes mid-session.
- [x] 6. `EngineTrace`: legacy `Config` decoding; `TraceReplayTest` green without edits to traces.
- [x] 7. `data/restricted/AppPolicy.kt` + `LegacyPolicies.resolve` + unit tests (legacy keys →
      policies, confirmation wait on upgrade vs fresh install, 0-wait coercion).
- [x] 8. Repositories: `default_policy`, `policy_overrides`, `confirmation_wait_seconds`;
      `RestrictedAppsSnapshot` carries overrides.
- [x] 9. `SessionEngine.configFlow()` → `Config(rules, defaultRule)`.
- [x] 10. `AppBlockViewModel`: per-pkg policy, open vs. times-up wait, 0-s wait, `PICKING`
      phase (slider value, range 30/90).
- [x] 11. `AppBlockScreen` / `ReInterventionScreen`: picker (slider, quick buttons, "Need
      longer?", "Open for N min") + strings.
- [x] 12. `isPolicyLooser` + `RestrictionReducingTest` rewrite; confirmation screen uses the
      persisted confirmation wait.
- [x] 13. `PolicyEditor` composable; Defaults card; Safety card; per-app card (summary, Use
      defaults, editor).
- [x] 14. `SettingsViewModel`: draft per-app policies + defaults + confirmation wait, pending
      flags, Save merge with suggestion-added apps (as today).
- [x] 15. Strings: rename wait/grace labels, remove "Block again after grace".
- [x] 16. `ARCHITECTURE.md` §5.1, §5.2, §6.1, §7.3 and the AGENTS.md gotcha on the return window
      ("apps with Block again after grace off" → "Pause only apps").
- [ ] 17. `gradlew.bat testDebugUnitTest lintDebug assembleDebug` (Yotam).
- [ ] 18. Device checklist (Yotam):
  - upgrade install keeps old behaviour per app and the old wait as confirmation wait
  - each mode end-to-end
  - Pick with 0-s wait
  - Pick "Need longer?" up to 90
  - time's up in Pick → wait → another round
  - "+10 s" off hides the button
  - lowering the confirmation wait asks for the current one
  - "Restrict" from the suggestion popup → the app gets Time limit, 10 s, 5 min
  - upgrade: each app keeps its old wait / grace; a new app gets the 10 s default

## Implementation notes (2026-11-13)

Verified: the pure code compiles and its JVM tests pass (124 tests: `EngineCoreTest` unchanged
apart from `Config.legacy`, new `PerAppModesTest`, `EngineFuzzTest` with random modes / picked
lengths / rule changes mid-session, `TraceReplayTest` on the three recorded traces through the
legacy `Config` decoding, new `AppPolicyTest`, rewritten `RestrictionReducingTest`). A mutation
check (removing the 1–90 min clamp) is caught by both the fuzz test and `PerAppModesTest`.
Android / Compose code was **not** compiled in the session (no SDK): tasks 17–18 are open.

Deviations and details decided while implementing:
- No separate `PICKING` phase: `AppBlockViewModel.isPick` plus the existing `COMPLETED` phase. The
  picker is vertically centred (round 4); **Home stays the filled primary button** and "open for N min"
  takes the place of the outlined Continue / "keep using the app" (the existing design nudges
  toward Home). Quick chips only move the slider. On the re-intervention screen the picker is
  laid out from the start but hidden and disabled until the wait ends (no layout jump).
- With "+10 s" off, the 10-s pill still appears (dismissible, countdown only), then the final
  5-s countdown as usual; the engine also ignores `ExtendTapped`.
- `PolicyDraft`, `isPolicyLooser` and `isRestrictionReducing` live in `ui/settings/PolicyDraft.kt`
  (pure) instead of the top of `SettingsViewModel.kt`, so they are tested without Android.
- Confirmation wait: 1–7200 s. Legacy fallback: `wait_time_seconds` if stored, else 12 s for an
  install that finished setup (the old default), else 10 s.
- Card summary: `Time limit · 12 s · 5 min`, `Pick your time · 0 s`, `Pause only · 20 s · 15 min
  re-entry`. The settings screen is now one `LazyColumn`.
- Round 2 (same day): Defaults section, "Use defaults" switch and the `default_policy` key
  removed (D7); "Safety" renamed (D8). The first edit of an app on its card starts its draft from
  its saved policy. 123 JVM tests pass after the change.
- Round 3 (same day, Yotam): the block screen's "N attempts to open {app}" is now laid out from
  the start and clipped to the rising fill, so it is revealed with it (the count is read when the
  screen opens, and again when the wait ends). Pick your time apps drop that text once the picker
  shows. On the time's-up screen of a Pick app the picker no longer reserves space while waiting
  (the text sat too high) and replaces the text when it appears. Unrelated fix: the grace pill's
  window gets 12 dp / 8 dp of transparent room (shifted up to compensate) so the "10 s added"
  bounce and shadow are no longer clipped.
- Round 4: on both block screens the picker sits in the vertically centred area (where the
  attempts / "time's up" text was), above the buttons no longer.
