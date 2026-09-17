# No-re-block apps: session fix + 20-second return window

"No-re-block apps" = restricted apps with **"Block again after grace" turned OFF**
(`RestrictedAppsRepository.reInterventionDisabledPackages`).

Status: PLAN ONLY — nothing implemented. Findings come from reading the code (working tree of
2026-09-17, including the uncommitted changes to GracePeriodService / BlockingSharedState);
none of it was reproduced on a device. Each finding lists a repro so it can be confirmed with
`adb logcat -s ChillpillA11y GracePeriodService BlockingSharedState`.

## Proposal

### Intent
1. Make no-re-block apps behave predictably: after the grace period ends the user may stay in the
   app, but once they really leave, the next open shows the block screen.
2. New: for these apps, leaving for **less than 20 seconds** never re-triggers the block screen.

### Findings (bugs / quirks)

**F1 — Leaving a restricted app grants a brand-new full grace period (main bug).**
`ChillpillAccessibilityService.recordLeavingRestrictedApp` does
`setGraceValidUntil(prev, now + graceMs)` on *every* transition out of a restricted app, with no
check that the app still had a valid session. For a no-re-block app whose grace already expired
(user stayed inside), nothing is running that would clear this again, so:
- grace 5 min, Continue at 0:00, stay until 7:00, go home, reopen at 10:00 → **no block** (grace
  was silently renewed until 12:00);
- every further exit renews it again, so with exits shorter than the grace period the block screen
  never comes back. This is the "sometimes it blocks, sometimes it doesn't" quirk.
Repro: log shows `isInGracePeriod ... inGrace=true` on reopen long after `onGraceExpired`.

**F2 — The "exit" that triggers F1 is often not a real exit.**
The service listens to every `TYPE_WINDOW_STATE_CHANGED`, so the notification shade, volume/power
dialogs (`com.android.systemui`), some keyboards, the share sheet, permission dialogs and custom
tabs all count as "left the app" (F1 renewal) and overwrite `previousForegroundPackage`. If the
restricted app then emits no new window event when the overlay closes, `previousForegroundPackage`
stays e.g. `com.android.systemui`, and the *real* exit later is not recorded at all.

**F3 — Block screen can be bypassed by Home → reopen (all restricted apps, race).**
While the block screen is up, the target app usually still emits a late window event. The
same-app branch in `onAccessibilityEvent` then sets `currentForegroundPackage = X`, which defeats
the `currentForegroundPackage == our package` guard in `recordLeavingRestrictedApp`. Pressing Home
on the block screen is then treated as "left X voluntarily" → full grace granted → reopening X
skips the block. Order-dependent, so it appears random.

**F4 — In-memory state only.** `BlockingSharedState` and `previousForegroundPackage` die with the
process. After a process restart, the next in-app window change of a no-re-block app
(`prev == null`) shows a block screen in the middle of a session. Rare; low priority.

**F5 — Uncommitted GracePeriodService rewrite changes semantics for normal apps.** The new job
polls `isInGracePeriod`, and F1 keeps pushing that deadline on every exit (including F2 pseudo
exits), so re-intervention for normal apps can now be postponed indefinitely. Before the rewrite
the fixed timer masked this. Removing the renewal (task 2) fixes it; if the extension is wanted,
it needs a cap.

**F6 — Uncommitted `recordDismissedViaSystemGesture()` double-counts.** `onUserLeaveHint` also
fires when the activity itself starts Home ("Go Home") or the target app ("Continue"), so
`LEFT_APP` is recorded twice on Go Home and once, wrongly, on Continue. Not part of this feature;
listed because it is in the same in-flight change set. Fix: set a `leavingByButton` flag in
`goHomeAndFinish` / `launchTargetAppAndFinish` and skip the record when set.

Verified OK: Continue → same-app skip (no duplicate block); expiry while away → regular block on
return; expiry while inside a no-re-block app → no screen, stats event recorded; per-package grace
jobs (uncommitted) correctly stop one app's Continue from cancelling another app's timer.

### Scope
In: leave/return logic in the accessibility service, shared state, tests, ARCHITECTURE.md.
Out: UI, settings, statistics schema. 20 s applies to no-re-block apps only (one-line change to
extend to all apps later).

### Open decision (please confirm before implementation)
Task 2 removes "leaving extends the grace period" for **all** apps. The uncommitted
GracePeriodService comment treats that extension as intended. Recommended: remove it — grace is
"N minutes after Continue", full stop. Alternative: keep it but cap at one extension.

## Design

Replace "leaving grants grace" with an explicit per-package **session**:

`BlockingSharedState` gains
- `activeSessions: Set<String>` — package passed the block (Continue, or "Restrict" in the
  suggestion popup) and has not been blocked again since;
- `leftAtElapsed: Map<String, Long>` — `SystemClock.elapsedRealtime()` when the user left it
  (elapsedRealtime: immune to clock changes, keeps counting during sleep).

Pure decision function (unit-testable, clock injected), e.g. `service/SessionPolicy.kt`:

```
onContinue(pkg)            -> graceValidUntil = now + grace; activeSessions += pkg; leftAt -= pkg
onLeft(pkg)                -> if (pkg in activeSessions) leftAt[pkg] = now      // no grace change
onEnterFromElsewhere(pkg, noReblock):
    if now < graceValidUntil[pkg]                                   -> ALLOW
    if noReblock && pkg in activeSessions
       && leftAt[pkg] != null && now - leftAt[pkg] < 20_000          -> ALLOW (overstay continues)
    else -> BLOCK; activeSessions -= pkg; leftAt -= pkg; graceValidUntil -= pkg
on ALLOW                   -> leftAt -= pkg
```

Properties: the 20 s rule is evaluated lazily on return (no extra timer); showing a block ends
the session, so Home-from-block can never grant anything (fixes F3 regardless of event order);
an exit at 4:55 with return at 5:10 of a 5-min grace is allowed for no-re-block apps.

"Left" detection hardening (F2):
- ignore window events from overlay-type packages — `com.android.systemui` and the enabled input
  methods (`InputMethodManager.enabledInputMethodList`) — they neither update
  `previousForegroundPackage` nor call `onLeft`;
- because lock-screen events are then ignored too, register `ACTION_SCREEN_OFF` in the
  accessibility service: call `onLeft(prev)` and reset `previousForegroundPackage = null`, so
  unlocking always re-evaluates entry (>= 20 s locked → block).
- Share sheet / permission dialog / custom tab still count as leaving; under the new rule they only
  matter after 20 s, which is acceptable.

Constant: `RETURN_WINDOW_MS = 20_000L` in `SessionPolicy`.

## Tasks

- [ ] 0. Confirm the open decision above; commit or stash the in-flight working-tree changes first
        so this lands as a separate diff.
- [ ] 1. Add `SessionPolicy` (+ state in `BlockingSharedState`) with injected clock.
- [ ] 2. `recordLeavingRestrictedApp`: drop `setGraceValidUntil`; call `onLeft(prev)`; keep the
        "don't count our own block screen" guard.
- [ ] 3. `processEvent`: replace `isInGracePeriod` check with `onEnterFromElsewhere(pkg, noReblock)`
        (read `reInterventionDisabledPackages` next to `restrictedPackages`).
- [ ] 4. `AppBlockViewModel.onContinue` and suggestion `onRestrict`: call `onContinue(pkg)`.
- [ ] 5. Overlay-package filter + `ACTION_SCREEN_OFF` receiver (register in `onServiceConnected`,
        unregister in `onDestroy`).
- [ ] 6. GracePeriodService: no logic change needed once task 2 lands; update its KDoc (deadline
        no longer moves). Remove dead `graceExpiredForPackages` plumbing if still unused.
- [ ] 7. F6 fix (`leavingByButton` flag) — optional, separate commit.
- [ ] 8. Optional (F4): persist sessions/leftAt/graceValidUntil via the unused `BlockSharedState`
        prefs wrapper; restore on service connect. Needs wall-clock for persisted values.
- [ ] 9. Unit tests for `SessionPolicy`: F1 scenario → BLOCK; exit 19 s → ALLOW; exit 21 s → BLOCK;
        exit across grace expiry <20 s → ALLOW; normal app after expiry, exit 5 s → BLOCK;
        Home from block then reopen → BLOCK; two apps interleaved.
- [ ] 10. Device pass with logcat for: shade open 60 s inside overstayed app (no block), lock 30 s
         (block), recents round-trip 5 s (no block), F3 repro (block).
- [ ] 11. Update ARCHITECTURE.md §6 (shared state table, service flow).
