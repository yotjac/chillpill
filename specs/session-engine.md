# Session engine: one source of truth for the blocking pipeline

Status: IMPLEMENTED 2026-09-24 (tasks 1–15). Engine unit tests (56, incl. 10 000 fuzz sequences)
pass on a plain JVM; the Android build, lint and device pass (tasks 16–17) are still open — the
implementing session had no Android SDK. See "Implementation notes" for deviations from the Design.

Supersedes the state-handling parts of `specs/no-reblock-apps-session-fix.md` and
`specs/grace-expiry-warning.md` (their product behaviour stays, except where "Behaviour changes"
below says otherwise). Those specs remain as decision records.

## Proposal

### Problem
The blocking pipeline has no single owner of "where is the user, and what is each restricted app
allowed to do". The answer is spread over six mutable values fed by different signals:

| Value | Owner | Fed by |
|---|---|---|
| `previousForegroundPackage`, `screenOffPackage`, `screenOffGeneration` | a11y service | window events, screen broadcasts |
| `currentForegroundPackage` | `BlockingSharedState` | a11y service (other rules than the above) |
| foreground at grace expiry | `GracePeriodService` | fresh UsageStats query |
| `leftAtElapsed`, grace deadline, extensions | `SessionPolicy` | calls from 4 different components |

They are never reconciled against reality, decisions are timestamped with *processing* time, and
every past bug was fixed by another patch between them (generation counter, retry loops,
`startsInFlight`, `lastStartId`, class-name rules). Once one value is wrong it stays wrong.

Reported symptoms (2026-09-24), both "from time to time":
- **S1** A restricted app with "Block again after grace" OFF shows the block screen although the
  user was away for less than 10 s.
- **S2** The 5-second countdown does not appear before a restricted app is blocked again.

Plausible common cause (from reading the code, not reproduced): after unlock the launcher emits a
window event, then the restricted app X resumes *without* one (documented device behaviour). The
a11y service now believes the launcher is in front while the user is in X:
- the warning pill is gated on `currentForegroundPackage == X` → never published (**S2**), while
  expiry asks UsageStats, sees X, and re-intervenes without warning;
- X's next window event is judged as an entry against the screen-off `leftAt` → block after a
  short absence (**S1**). The same state also makes the *real* next exit go unrecorded.

A process restart (crash, update, OEM battery killer) produces S1 too: all sessions are in memory.

### Intent
Replace the scattered state with a **session engine**: a pure, single-threaded state machine that
owns presence ("who is in front, since when") and per-app session state, consumes timestamped
inputs, and emits effects. Android components become thin adapters that feed inputs and render
outputs. Every decision becomes unit-testable on the JVM, and real-device event sequences can be
recorded and replayed as tests.

### Decisions (with Yotam, 2026-09-24)
- **Scope:** the blocking / grace / warning / suggestion pipeline only. Settings, statistics,
  home and setup screens are untouched.
- **One go:** replaced in a single change, no shadow mode. Compensated by the scenario + fuzz
  tests and the manual device checklist below.
- **No session persistence** (for now). Losing in-memory state fails strict (next event from a
  restricted app blocks); it never grants extra access. Instead the engine logs *why* the previous
  process died (Android 11+ exit reasons), so we learn whether restarts cause S1 before paying for
  persistence. The engine state is one plain object, so persisting it later is a small change.
- **5-second countdown always shows** before a re-block, unless the 10-second pill is still up.
- **Suggestion popup closes when the user leaves its app** (B2), unanswered; accepted.
- Reconciliation every 5 s and trace export via adb only (no UI) accepted as proposed.

### Behaviour changes (everything else must behave as today)
- **B1 — Final countdown is unconditional.** With re-intervention ON, the non-dismissible 5 s
  countdown appears in the last 5 s whenever the 10 s pill is not showing: after "+10 s", after
  the 10 s pill was tapped away (×), and when the user entered the app with <5 s left. Today it
  only appears after "+10 s".
- **B2 — Suggestion popup no longer freezes the pipeline.** Today every window event is ignored
  while the suggestion popup is up, so no other restricted app can be blocked. New: events are
  always processed; the popup closes when the user leaves the app it is about (no answer given,
  so it is not re-asked in this process, same as today's dedupe).
- **B3 — Grace notification is cosmetic.** `GracePeriodService` no longer runs timers; if it fails
  to start or is stopped by the user, grace is still enforced. Today such a failure ends the
  session early (next open blocks).
- **B4 — Presence self-heals.** While any session exists, the engine checks UsageStats every 5 s
  and on screen-on and corrects its belief when a newer RESUME disagrees with it.

### Non-goals
- No change to the waiting screen, re-intervention screen, pill visuals, suggestion dialog visuals,
  statistics schema or any settings behaviour. No new user-facing UI (so no visual demo).
- No DI framework, no repository interfaces, no Clean Architecture layers (AGENTS.md conventions).
- No persistence of sessions (see above).

## Design

### Overview
```
 Signal adapters (Android)          Engine (pure Kotlin, one thread)          Output adapters (Android)
 ─────────────────────────          ────────────────────────────────          ─────────────────────────
 a11y window event ─classify─┐                                          ┌──▶ BlockLauncher (activities)
 screen off / on / present ──┤      ┌─────────────────────────────┐     ├──▶ StatsRecorder (Room)
 UsageStats probe result ────┼────▶ │ EngineCore.handle(input)    │ ────┼──▶ RestrictedApps / Suggestion repos
 block activity lifecycle ───┤      │   presence  +  app states   │     ├──▶ ProbeRunner (UsageStats)
 Continue / +10 s / × / sugg.┤      │   → effects + derived view  │     ├──▶ SuggestionEvaluator
 config (DataStore, hot) ────┤      └─────────────────────────────┘     └──▶ StateFlows: warning, suggestion,
 wake-up timer (Tick) ───────┘                                                graceActive → pill, popup, notification
```
`SessionEngine` (Android-aware host, singleton in `ChillpillApp`) owns a `Channel<Input>`, runs
`EngineCore` on one coroutine on `Dispatchers.Default.limitedParallelism(1)`, executes effects,
publishes derived state as `StateFlow`s, keeps a single wake-up timer, and writes the trace.

`EngineCore` has **no Android imports and no suspension points**: `handle(input): List<Effect>`
mutates its own state synchronously. That removes the reason for `screenOffGeneration`: nothing
can interleave inside a decision.

### Time
- Every input carries `at` = `SystemClock.elapsedRealtime()` of **when the thing happened**, not
  when it was processed. Monotonic and counts through sleep; immune to wall-clock changes.
- a11y events: `at = elapsedNow - (uptimeNow - event.eventTime)` (eventTime is uptime-based).
- UsageStats RESUME timestamps (wall clock) convert the same way: `elapsedNow - (wallNow - ts)`.
- All deadlines are elapsed-based. The pill countdown uses `SystemClock.elapsedRealtime()` too.
- Before handling any input the core runs `advanceTo(input.at)`, which processes every deadline
  that fell due before it (in deadline order). A late or missed wake-up (deep sleep: coroutine
  `delay` does not advance while the CPU sleeps) is therefore caught up by the next input,
  ScreenOn at the latest, with the same outcome as an on-time timer.

### Model (`service/engine/EngineModel.kt`)
```kotlin
enum class WindowKind { APP, APP_OVERLAY, OWN_MAIN_UI, OWN_BLOCK_UI, OWN_OVERLAY, SYSTEM_OVERLAY }
enum class BlockKind { INITIAL, RE_INTERVENTION }
enum class CloseReason { GO_HOME, BACK, SYSTEM_GESTURE, CONTINUE }
enum class SuggestionAnswer { RESTRICT, IGNORE, NEVER }

data class Config(val restricted: Set<String>, val noReblock: Set<String>, val graceMs: Long)

data class Presence(
    val pkg: String?,          // null: screen off / locked / unknown
    val kind: WindowKind?,     // APP, OWN_MAIN_UI or OWN_BLOCK_UI
    val since: Long,           // elapsed ms of the event that put it there
    val screenOn: Boolean,
)

sealed interface AppState {
    data class Blocking(val kind: BlockKind, val shownAt: Long, val uiVisible: Boolean, val instance: Long?) : AppState
    data class Session(
        val graceDeadline: Long,
        val extensionsUsed: Int = 0,
        val firstWarningDismissed: Boolean = false,
        val graceOver: Boolean = false,  // no-reblock apps only: grace ran out, user may stay
        val leftAt: Long? = null,        // non-null ⇔ user is not in the app
    ) : AppState
}   // Idle = no entry in the map

sealed interface Input { val at: Long
    Window(pkg, className, kind, at) · ScreenOff(at) · ScreenOn(at, keyguardLocked) · UserPresent(at)
    Probe(result: ProbeResult?, token, at)          // ProbeResult(pkg, kind, resumedAt)
    BlockStarted(pkg, instance, at) · BlockStopped(pkg, instance, at) · BlockClosed(pkg, reason, instance, at)
    ContinueTapped(pkg, at) · ExtendTapped(pkg, at) · WarningDismissed(pkg, at)
    SuggestionEligible(pkg, appName, at) · SuggestionAnswered(pkg, answer, at)
    ConfigChanged(config, at) · Tick(at) · ProcessStarted(exitInfo, at)
}

sealed interface Effect {
    ShowBlock(pkg, kind) · RecordUsage(pkg, kind) · RequestProbe(token)
    EvaluateSuggestion(pkg) · PersistSuggestionAnswer(pkg, answer)   // RESTRICT also persists the restriction
}

data class WarningUi(val pkg: String, val deadline: Long, val canExtend: Boolean, val dismissible: Boolean)
data class SuggestionUi(val pkg: String, val appName: String)
```

### Window classification (`WindowClassifier.kt`, pure)
Same rules as today, moved into one tested function with the lookups injected:

| Event | Kind |
|---|---|
| own package, `MainActivity` / `ChillpillSettingsActivity` | `OWN_MAIN_UI` |
| own package, `AppBlockActivity` / `ReInterventionActivity` | `OWN_BLOCK_UI` |
| own package, anything else (pill, suggestion popup) | `OWN_OVERLAY` |
| `com.android.systemui` | `SYSTEM_OVERLAY` |
| enabled-IME package and class is not one of its activities (or unknown) | `SYSTEM_OVERLAY` |
| any other package, class is an activity of it | `APP` |
| any other package, class is known *not* to be one of its activities (dialog, bottom sheet, popup, toast) | `APP_OVERLAY` |
| any other package, class null (probe) | `APP` |
| package not visible to us (no launcher activity, not an IME), framework class (`android.*`) | `APP_OVERLAY` |
| package not visible to us, any other class | `APP` |

Positive evidence only (added after the Instagram-comments bug): presence moves for a window
that is known to be an *activity*. Non-activity windows float over the app in front without
pausing it, so if one were counted as a foreground change the engine would believe the user left,
and, since the app underneath never resumes again, no UsageStats probe could ever repair that. The
session then ended silently "while away" (G3) and the app's next real event blocked it (E2).
Cost: an app's starting (splash) window is a `FrameLayout` of its package that arrives ~300–450 ms
before the activity's event, so an initial block now fires on the activity event, that much later.

Change vs today: SystemUI is **always** an overlay. The "count SystemUI as a foreground change
when there is no session" rule existed only so block → shade → tap X notification re-evaluates X.
The engine handles that through the block screen's own lifecycle (rule E3 below) instead.

### Presence rules (`EngineCore`)
- **P1 Window(APP or OWN_MAIN_UI, X).** Ignore if `at < presence.since` (stale). If
  `X != presence.pkg`: `leave(presence.pkg, at)`, set presence to X since `at`, then
  `evaluate(X, at, entry = true)`. If `X == presence.pkg`: `evaluate(X, at, entry = false)`.
- **P2 Window(OWN_BLOCK_UI).** `leave(presence.pkg, at)`, presence = own package / OWN_BLOCK_UI.
- **P3 Window(OWN_OVERLAY, APP_OVERLAY or SYSTEM_OVERLAY).** Ignored. Never changes presence
  (SYSTEM_OVERLAY over our block screen excepted: the shade rule).
- **P4 ScreenOff.** `leave(presence.pkg, at)`, remember `screenOffPkg`, presence = null,
  `screenOn = false`. Pill and popup disappear (derived).
- **P5 ScreenOn(locked = false) / UserPresent.** `screenOn = true`; start a probe burst: 4 probes,
  400 ms apart (effect `RequestProbe`, wake-ups scheduled by the core). ScreenOn with the keyguard
  locked only sets `screenOn` and waits for UserPresent.
- **P6 Probe(result).** Ignored while the screen is off. Applied as `Window(result.pkg, APP,
  at = result.resumedAt)` when the result is `APP`, differs from `presence.pkg`, and
  `result.resumedAt > presence.since` — i.e. UsageStats knows about a *newer* switch than our
  last window event. Otherwise ignored (an older RESUME never overrides a newer window event). If a
  burst ends with presence still null and every probe inconclusive, apply
  `Window(screenOffPkg, APP, at = burst start)` (today's fallback).
- **P7 Reconciliation.** While any `Session` exists and the screen is on: one probe every 5 s,
  and one 1.5 s before each grace deadline. Same rule as P6. This is what repairs S1/S2's stale
  launcher state: X's RESUME after unlock is newer than the launcher's window event, so presence
  becomes X *as of X's resume time*, and the return-window check uses that time.

`leave(X, at)`: if X has a `Session` with `leftAt == null`, set `leftAt = at`. If the suggestion
popup is about X, close it (B2).

### Session rules (`EngineCore`)
`evaluate(X, at, entry)` for a **restricted** X:

| # | State of X | Condition | Result |
|---|---|---|---|
| E2 | Idle | otherwise | **block**: `Blocking(INITIAL, at, uiVisible = false)`, `ShowBlock`, `RecordUsage(OPEN_ATTEMPT)` |
| E3 | Blocking | `uiVisible` or `at - shownAt < 2 s` | nothing (block already up / still launching). Additionally, while presence is our block screen, such an event does not even change presence (**absorb**, P1): it is the app's own late splash→main event, not the user getting past the block |
| E4 | Blocking | not visible for ≥ 2 s | **re-show** the same block kind (user got to X past a stopped block screen: shade → notification, recents) |
| E5 | Session | `entry == false` | nothing (user is still inside) |
| E6 | Session | `entry`, `at < graceDeadline` | allow; `leftAt = null` |
| E7 | Session | `entry`, no-reblock, `leftAt != null`, `at - leftAt < 10 s` | allow; `leftAt = null` |
| E8 | Session | `entry`, otherwise | end session → rule E2 |

For a **non-restricted** X with `entry` and kind `APP`: effect `EvaluateSuggestion(X)`.

Block screen and user actions:

| # | Input | Rule |
|---|---|---|
| A1 | `BlockStarted(X, id)` / `BlockStopped(X, id)` | Started: `uiVisible = true`, `instance = id`. Stopped (same id only): `uiVisible = false`, schedule follow-up probes (+0.3 s, +1 s); if presence is X, re-show (E4) |
| A2 | `ContinueTapped(X)` while `Blocking` | `Session(graceDeadline = at + graceMs, leftAt = at)`, `RecordUsage(WAIT_COMPLETED, sessionStart)`; the activity then launches X, whose window event is an entry allowed by E6 (`leftAt = at` because the user is still on the block screen — keeps I2 true) |
| A3 | `ContinueTapped(X)` otherwise | ignored (double tap) |
| A4 | `BlockClosed(X, reason, id)` while `Blocking` with the same id | Idle, `RecordUsage(LEFT_APP)` — exactly once, by state, so the `leavingByButton` flag goes away; follow-up probes; if presence is X, block again (E2) |
| A5 | `ExtendTapped(X)` | if in grace and `extensionsUsed < 1`: `graceDeadline += 10 s`, `extensionsUsed++`, `RecordUsage(GRACE_EXTENDED)` |
| A6 | `WarningDismissed(X)` | `firstWarningDismissed = true` |
| A7 | `ConfigChanged` | replace config; drop the state of packages no longer restricted |
| A8 | `SuggestionAnswered(X, RESTRICT)` | add X to the config's restricted set locally (DataStore catches up later), `Session(at + graceMs)`, `PersistSuggestionAnswer` |
| A9 | `SuggestionAnswered(X, IGNORE / NEVER)` | `PersistSuggestionAnswer`; close popup |
| A10 | `SuggestionEligible(X, name)` | show popup only if presence is still X and the screen is on |

Grace expiry (`advanceTo`, for every `Session` with `!graceOver` and `graceDeadline ≤ at`),
evaluated against presence *as it was at the deadline*:

| # | Case | Result |
|---|---|---|
| G1 | no-reblock | `graceOver = true`, `RecordUsage(GRACE_EXPIRED_WHILE_ACTIVE / _AWAY)`; session stays (E7 still applies) |
| G2 | re-intervention, presence == X, screen on | `Blocking(RE_INTERVENTION)`, `ShowBlock`, `RecordUsage(OPEN_ATTEMPT)` + `GRACE_EXPIRED_WHILE_ACTIVE` |
| G3 | re-intervention, otherwise | Idle, `RecordUsage(GRACE_EXPIRED_WHILE_AWAY)` |

G2 and the pill read the **same** presence value. That is the structural fix for S2: the pill
cannot be gated on one belief while expiry uses another.

### Derived state (recomputed after every input, published only when it changes)
- **Warning pill** — for `P = presence.pkg`, when the screen is on, P is restricted with
  re-intervention ON and P's `Session` is in grace (`rem = graceDeadline - now > 0`):
  - `rem ≤ 10 s` and `extensionsUsed == 0` and `!firstWarningDismissed` → first warning
    (`canExtend = true, dismissible = true`);
  - else `rem ≤ 5 s` → final countdown (`canExtend = false, dismissible = false`) — **B1**;
  - else none.
  Only one pill can exist because presence names one package (today's R3 holds by construction).
- **Suggestion popup** — `SuggestionUi` or null.
- **graceActive** — any `Session` with `!graceOver` → notification service on/off.
- **Next wake-up** — min over: each session's `deadline - 10 s`, `deadline - 5 s`,
  `deadline - 1.5 s` (probe), `deadline`; pending probe-burst slots; next reconciliation probe.
  The host keeps one `delay` job to the earliest one and sends `Tick`.

### Invariants (checked after every step in tests; logged, never crash, in production)
- I1 A `Session` or `Blocking` exists only for a restricted package.
- I2 For every `Session`: `leftAt == null` ⇔ `presence.pkg == X && screenOn`.
- I3 A warning is shown only for `presence.pkg`, with re-intervention ON, while in grace.
- I4 Every `Session` with `!graceOver` has its deadline among the scheduled wake-ups.
- I5 Leaving never moves a deadline; only A2, A5 and A8 do.
- I6 Every transition out of `Blocking` goes to `Session` (A2) or Idle with exactly one `LEFT_APP`
  (A4), or re-show (E4).

### Host (`SessionEngine.kt`)
- Created lazily in `ChillpillApp` (manual DI), started by the a11y service's `onServiceConnected`
  (idempotent). Collects `RestrictedAppsRepository.snapshot` + `SettingsRepository.settings` as
  hot flows into `ConfigChanged`; inputs queue in an unlimited channel until the first config
  arrives, so no decision is made without config and no DataStore read happens inside one.
- `send(input)` is thread-safe and non-suspending (used from activities, overlays, receivers).
- Executes effects off the engine thread: `ShowBlock` → start activity (a11y context, same
  flags as today); `RecordUsage` → Room on IO; probes → `ForegroundPackageQuery` on IO → `Probe`
  input; `EvaluateSuggestion` → `SuggestionEvaluator`. Effect failures are logged and, where it
  matters, fed back (a failed `ShowBlock` leaves `Blocking` invisible, so E4 retries).
- Exposes `warning`, `suggestion`, `graceActive` as `StateFlow`s.

### Adapters
- **`ChillpillAccessibilityService`** shrinks to: classify each `TYPE_WINDOW_STATE_CHANGED` and
  send `Window`; register the screen receiver and send `ScreenOff/On/UserPresent`; collect
  `engine.warning` → `GraceWarningOverlayManager`, `engine.suggestion` →
  `SuggestionOverlayManager` (both must stay here: only an a11y service may add
  `TYPE_ACCESSIBILITY_OVERLAY`). The pill's buttons send `ExtendTapped` / `WarningDismissed`; the
  manager's own `dismissedFor` logic goes (the engine owns it).
- **`ForegroundPackageQuery`** returns `ProbeResult(pkg, resumedAtWall)` instead of a bare package.
- **`SuggestionEvaluator`** (new, `service/`): the non-engine half of today's
  `handleNonRestrictedApp` — excluded list, launcher check, `AppOpenTracker`, 12 h usage query,
  ignore/exclusion repos — then sends `SuggestionEligible`. `PersistSuggestionAnswer` /
  `PersistRestricted` run through the existing repositories.
- **`AppBlockActivity` / `ReInterventionActivity`**: report `BlockStarted` / `BlockStopped` from
  `onStart` / `onStop` and `BlockClosed(reason)` when finishing; no longer touch sessions or
  start `GracePeriodService`. `AppBlockViewModel` keeps the wait timer and the 24 h counter;
  `onContinue` sends `ContinueTapped` and no longer reads settings or records `WAIT_COMPLETED`.
- **`GraceNotificationService`** (renamed `GracePeriodService`): started / stopped from
  `engine.graceActive`; shows the ongoing notification; no timers, no session logic (B3).
- **`GraceWarningPill`**: `deadline` becomes elapsed-based; everything else unchanged.

### Diagnostics
- **Trace.** Every input and every effect is appended as one JSON line (Gson) to
  `filesDir/trace/trace-N.jsonl`, rotating at 512 KB, two files kept, buffered and flushed every
  2 s and on `ShowBlock`. The config is written at start and on every change so a trace can be
  replayed on its own. Pull on a debug build with
  `adb exec-out run-as com.chillpillapp cat files/trace/trace-0.jsonl`. (An in-app "export
  diagnostics" button is a possible follow-up; it needs its own UI spec.)
- **Replay.** `TraceReplayTest` loads a trace from `app/src/test/resources/traces/`, feeds it to a
  fresh `EngineCore`, and checks the invariants plus the assertions written for that trace. A
  device bug becomes: pull trace → drop into resources → write the expected outcome → fix.
- **Process exit reasons.** On engine start (API 30+), the last
  `ActivityManager.getHistoricalProcessExitReasons` entry is logged and written to the trace as
  `ProcessStarted`, so we can see whether S1 ever follows a crash, update or kill.

### Package layout after the change
```
service/
  engine/
    EngineModel.kt        Input, Effect, AppState, Presence, Config, WarningUi, SuggestionUi
    WindowClassifier.kt   pure classification
    EngineCore.kt         pure reducer: presence + sessions + derived state + wake-ups
    SessionEngine.kt      host: channel, config collection, timer, effects, StateFlows
    EngineTrace.kt        JSONL trace writer
    ProcessExitLogger.kt  exit reasons → trace
  ChillpillAccessibilityService.kt   adapter (signals in, overlays out)
  GraceNotificationService.kt        notification only (renamed from GracePeriodService)
  SuggestionEvaluator.kt             suggestion eligibility (I/O)
  ForegroundPackageQuery.kt, GraceWarningOverlayManager.kt, GraceWarningView.kt,
  SuggestionOverlayManager.kt        (kept, adjusted)
Deleted: service/BlockingSharedState.kt, service/SessionPolicy.kt (rules folded into EngineCore;
tests ported), data/blockstate/BlockSharedState.kt (unused).
```

### Risks
- **R1 Reconciliation cost.** A UsageStats query every 5 s while a session exists and the screen
  is on. The query is bounded to the last 10 min; if it shows up in battery stats, widen to 10 s.
- **R2 Probe corrections are only as good as UsageStats.** P6 only ever applies a *newer* RESUME,
  so the worst case is today's behaviour (no correction), not a wrong one.
- **R3 E4 timing.** 2 s launch guard: a block that launches slower than that could be shown
  twice. The instance id makes the replaced screen's close harmless; check on a slow device.
- **R4 Big-bang change.** No shadow mode, by decision. Mitigated by the scenario, invariant and
  fuzz tests, and the device checklist (task 16), which merges the open device lists of the two
  earlier specs.
- **R6 Recheck timing.** If a launcher's window event arrives more than 700 ms after a Home swipe
  *and* the blocked app sent a late event more than 2 s after its block appeared, a false block
  shows over the home screen. Check on a slow device with gesture navigation (task 17 l).
- **R5 FGS start from the background.** Moving the notification service start into the engine
  means it can start while no Chillpill activity is visible. If Android refuses, only the
  notification is missing (B3); logged.

## Implementation notes (2026-09-24)

- **E1 dropped.** "Ignore events older than the last close" would also have ignored the app's own
  event in block → shade → tap notification whenever that event raced the close, i.e. a bypass.
  Replaced by the absorb rule (E3/P1) plus presence checks and follow-up probes on
  `BlockStopped` / `BlockClosed` (A1, A4). Tests: `lateEventThenGoHome_noSecondBlock`,
  `shadeNotificationPastBlock_blocksAgain`, `recentsPastBlock_reshows_andOldInstanceCloseIgnored`.
- **Review fixes (independent review, same day):**
  - *Bypass without usage access.* Absorbing every event of the blocked app while its screen was
    "visible" left getting past the block to the UsageStats probes, which return nothing when usage
    access is off (it is optional). Now: absorb only within `ABSORB_MS` (2 s) of the block
    appearing; a SystemUI / keyboard window over our block screen moves presence (so the app
    reached from the shade is a real foreground change); Go Home / Back never re-block; and a
    screen-off while the block is in front remembers the blocked app as the unlock fallback.
  - *Recheck instead of an immediate re-block (second review round).* When the block screen stops or
    closes by a system gesture while its app looks like the one in front, the core waits
    `RECHECK_DELAY_MS` (700 ms): any other app coming to the front cancels it (a Home swipe after a
    slow cold start's late event), otherwise the block is shown again. Without this, a late event
    more than 2 s after the block plus a Home swipe produced a false block over the home screen.
  - *Endless probing.* Reconciliation only runs for live sessions: grace running, the user inside,
    or a no-re-block app left less than 15 s ago (10 s window + one probe interval). The session
    itself is kept, so a late probe can still prove a quick return (removing it re-opened S1 when
    UsageStats was slow).
  - *Notification service race.* The engine starts it on every false→true transition it
    processes; the service stops with `stopSelfResult(lastStartId)`, which Android ignores when a
    newer start is pending (a plain `stopSelf()` there could crash the process); a start delivered
    after grace already ended stops itself in `onStartCommand`.
  - *"+10 s" confirmation* moved into the engine: an accepted extension yields
    `WarningUi(extensionConfirmed = true)` for `EXTEND_CONFIRMATION_MS` (1.2 s). A rejected (late)
    tap or leaving the app shows nothing.
  - *Config read failures* are retried instead of stopping the engine.
  - E5/E7 in code are slightly more lenient than the table when `leftAt` is inconsistent with
    presence (treat as entry; `leftAt == null` counts as "never left"); unreachable while I2 holds.
- **Block instance ids.** A re-shown block replaces the old activity (`CLEAR_TOP`), whose late
  close must not end the new block. Each block screen reports under
  `AppBlockViewModel.instance`; the core only accepts stop / close from the instance that last
  reported `BlockStarted`.
- **`PersistRestricted` merged** into `PersistSuggestionAnswer(RESTRICT)`.
- **I2 at Continue:** the new session gets `leftAt = at` while the block screen is in front, so
  "leftAt is null exactly while the user is in the app" holds from the start; the app's next
  window event is the entry that clears it.
- **Notification service** watches `engine.graceActive` and stops itself; the engine only starts it.
- Engine tests run on a plain JVM (no Android): `EngineCoreTest` (44 scenarios),
  `EngineFuzzTest` (10 000 random sequences × 60 steps, invariants + I5 after every step),
  `WindowClassifierTest`, `TraceReplayTest` (codec round trip, rotation, a hand-written S1 trace).
  Mutation-checked: disabling the probe correction, the screen-off exit or the absorb rule each
  makes tests fail.

## Tasks

- [x] 1. Review this spec (Yotam). Open points: B2 popup behaviour, R1 interval, trace via adb only.
- [x] 2. `EngineModel.kt` and `WindowClassifier.kt` + `WindowClassifierTest` (every row of the table,
        IME-with-activities case).
- [x] 3. `EngineCore`: presence rules P1–P7, session rules E1–E8, A1–A10, G1–G3, `advanceTo`,
        derived state, next wake-up. No Android imports.
- [x] 4. Invariant checker (I1–I6) used by every core test.
- [x] 5. Scenario tests in `EngineCoreTest`: port every `SessionPolicyTest` case; S1 stale-launcher
        repro (allow when back < 10 s by resume time); S2 repro (pill and re-intervention agree);
        B1 matrix (ignore / × / +10 s / enter with 7 s and 3 s left); splash→main; Go Home + late
        event (E1); block → shade → notification (E4); screen off 5 s / 30 s with and without
        keyguard; probe-burst fallback; expiry during deep sleep caught up on ScreenOn; two apps in
        grace; config removes an app mid-session; suggestion restrict / ignore / leave (B2);
        fresh core after "process restart" blocks on next event.
- [x] 6. Randomised fuzz test: 10 000 random input sequences over 3 packages, invariants after
        every step.
- [x] 7. `SessionEngine` host: channel, first-config gate, effect execution, wake-up timer,
        StateFlows, wiring in `ChillpillApp`.
- [x] 8. `EngineTrace` + `ProcessExitLogger`; `TraceReplayTest` with one hand-written trace.
- [x] 9. `ForegroundPackageQuery` returns `ProbeResult` with resume time.
- [x] 10. Rewrite `ChillpillAccessibilityService` as an adapter; delete its session state.
- [x] 11. Pill: elapsed-based deadline; `GraceWarningOverlayManager` renders `engine.warning`, drop
         `dismissedFor`; buttons send inputs.
- [x] 12. `SuggestionEvaluator`; `SuggestionOverlayManager` renders `engine.suggestion`; remove the
         `isShowing` gate.
- [x] 13. Block activities + `AppBlockViewModel` send inputs; remove `leavingByButton`, direct
         session calls and service starts.
- [x] 14. `GracePeriodService` → `GraceNotificationService` (manifest, strings unchanged).
- [x] 15. Delete `BlockingSharedState`, `SessionPolicy`, `SessionPolicyTest` (after the port),
         `data/blockstate/`. Update `ARCHITECTURE.md` (§3, §5.5, §6, §9, §10), the AGENTS.md gotchas
         (most become obsolete; add "all pipeline state lives in EngineCore"), and put a
         "superseded in part by specs/session-engine.md" line at the top of the two older specs.
- [ ] 16. `./gradlew testDebugUnitTest lintDebug assembleDebug` green.
- [ ] 17. Device pass (grace 1 min, `adb logcat -s SessionEngine ChillpillA11y`, pull the trace
         after each failure):
         (a) S1: no-reblock app, overstay, home 5 s → back: no block; 15 s → block;
         (b) S1 after unlock: lock 3 s, unlock (launcher flashes), stay in app: no block on next dialog;
         (c) S2: ignore 10 s pill → countdown continues to 0 → re-intervention;
             × at 8 s → 5 s countdown appears; +10 s → 5 s countdown; enter with 3 s left → countdown;
         (d) splash→main: one block screen, not two;
         (e) block → Go Home → nothing; block → shade → tap app's notification → block again;
         (f) block → recents → app: block again;
         (g) shade / keyboard / volume inside an app in grace: no pill loss, no block;
         (h) two restricted apps in grace, switch between them: correct pill per app;
         (i) suggestion popup: restrict (enters without block), ignore, leave app (popup closes);
         (j) force-stop the grace notification: grace still enforced;
         (k) reinstall the app mid-grace, reopen: block (expected), exit reason in trace;
         (l) cold-start a heavy restricted app (block appears), swipe Home a few seconds later: no
             second block over the home screen; same with usage access turned OFF for (e) and (f).
