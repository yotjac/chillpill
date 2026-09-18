# Grace-expiry warning pill with a single "+10 s"

Status: IMPLEMENTED (tasks 1–8) — 2026-09-18. Tasks 9–12 (visual-demo review, `./gradlew
testDebugUnitTest lintDebug`, device pass and the I3 logcat check) are still open; they need the
Android SDK / a device, which the implementing agent could not reach. Written 2026-09-18 from the
working tree of that day
(`GracePeriodService` polling per-package jobs, `SessionPolicy`, `ForegroundPackageQuery`).
Nothing here has been run on a device yet.

## Proposal

### Intent
When the grace period of a restricted app is about to run out **and the user will be kicked out
of it** (re-intervention enabled — "Block again after grace" ON), show a small, non-modal pill over
the app for the last 10 s: a countdown plus one **"+10 s"** button. Tapping it pushes the deadline
back once. If the user ignores it, the existing re-intervention happens exactly as today.

Being kicked out mid-sentence / mid-video is the most jarring moment of the blocking flow; a short
heads-up removes the surprise without weakening the block.

### Non-goals
- Apps with re-intervention disabled: they are never kicked out, so no pill.
- No change to `AppBlockActivity`, `ReInterventionActivity`, the wait timer, the suggestion
  overlay or any settings screen. Lead time and extension length are constants in v1.
- Not a general "snooze": one extension per grace window, then the normal re-block.

### Product decisions (confirmed 2026-09-18)
- **Extension: once only** per grace window, +10 s. A second tap is not offered.
- After the extension has been used, the pill comes back for the final 10 s as a
  **countdown only** (no button, dismissible), so the re-block is still announced.
- Lead time 10 s, extension 10 s.

### Amendment (2026-09-18): a second, non-dismissible warning at 5 s
Once the extension has been spent there is a **second** warning, and it is not the first one
continued: it appears at **5 s** (not 10), has no "+10 s" and no ×, and comes back even for a user
who tapped the first one away. Between 10 s and 5 s of an extended window there is no pill at all.

Reasoning: the user has already been warned once *and* bought themselves more time, so the re-block
is no longer a surprise and a long second warning would just nag — but a dismissed one would make it
a surprise again. So: late, short, unmissable. Same pill, same place, same size; only the buttons go.
This applies **only** after "+10 s" was used. A user who never extended still gets the first warning
for the whole last 10 s, with the button on offer, and their × still holds.

Lead times: `WARNING_LEAD_MS` (10 s) for the first warning, `FINAL_WARNING_LEAD_MS` (5 s) for the
second. `GracePeriodService` picks between them per tick on `canExtendGrace`, which is what
`dismissible` follows.

### UX requirements ("non-disturbing")
The pill must not change how the restricted app behaves underneath it:

1. It is a **window, not an activity** — the app is not paused; video, audio and scroll position
   are untouched (unlike `ReInterventionActivity`).
2. It **never takes focus** (`FLAG_NOT_FOCUSABLE`): an open keyboard stays open, immersive /
   fullscreen mode is not exited.
3. It only intercepts touches inside its own bounds (`WRAP_CONTENT` + `FLAG_NOT_TOUCH_MODAL`);
   the rest of the screen keeps working. Accessibility overlays are trusted windows, so the
   Android 12+ untrusted-touch block does not apply to the app below.
4. It sits at the **top**, below the status bar, small and slightly translucent; the bottom
   collides with nav bars, keyboards and comment fields.
5. It disappears on its own: when the user leaves the app, when the screen turns off, when
   grace expires (just before re-intervention), or when tapped away (×).
6. The × is absent from the second warning (the amendment above). Leaving the app, screen-off and
   expiry still take it down — only the user's own "dismiss" is withdrawn, never the app underneath.

### Blocking-flow invariants (must hold after this change)
- **I1 — Deadline only.** "+10 s" moves the grace deadline in `SessionPolicy` and nothing else:
  no session change, no `previousForegroundPackage` change, no `onLeft`. Expiry after an ignored
  or spent pill runs the unchanged `onGraceExpired` path.
- **I2 — Separate overlay manager.** The pill must not reuse `SuggestionOverlayManager` or its
  `isShowing` flag: `processEvent` skips *all* event handling while that flag is true, which would
  disable blocking of other restricted apps for the pill's lifetime.
- **I3 — No accessibility events from the pill.** `ChillpillAccessibilityService` listens to
  `TYPE_WINDOW_STATE_CHANGED`, which Android emits when a window gains focus. A non-focusable
  window never gains focus, so the pill should emit none. If it did, the own-package branch of
  `onAccessibilityEvent` would set `currentForegroundPackage = com.chillpill`, and the
  `ForegroundPackageQuery` fallback in `onGraceExpired` could then treat the user as "away" — and,
  worse, the pill's own visibility gate (`currentForegroundPackage == warning.packageName`) would
  fail for the rest of the grace window, so a pill taken down after an extension never returns.
  **This happened on the device (2026-09-18).** Matching the pill's class name was not enough: an
  overlay's window event does not necessarily carry its root view's class. The rule is therefore
  inverted — an own-package event is a foreground change only when its `className` is one of
  Chillpill's four activities; anything else is one of our overlays and is ignored outright. The
  pill's root view keeps its own class (`GraceWarningView`, an `AbstractComposeView` subclass —
  `ComposeView` is final) so such events are recognisable in logcat (task 12).

## Design

### Constants (`SessionPolicy.companion`)
```
const val WARNING_LEAD_MS        = 10_000L   // first warning (with "+10 s") appears at this
const val FINAL_WARNING_LEAD_MS  =  5_000L   // second warning, after the extension, appears at this
const val GRACE_EXTENSION_MS     = 10_000L
const val MAX_GRACE_EXTENSIONS   = 1
```

### SessionPolicy (pure, tested)
- New state: `extensionsUsed: HashMap<String, Int>`; reset in `startSession`, cleared in
  `endSession` and `clearGrace`.
- `graceRemainingMs(pkg): Long` — `max(0, graceValidUntil[pkg] - wallClock())`; 0 when absent.
- `canExtendGrace(pkg): Boolean` — in grace **and** `extensionsUsed[pkg] < MAX_GRACE_EXTENSIONS`.
- `extendGrace(pkg): Boolean` — if `canExtendGrace`: `graceValidUntil[pkg] += GRACE_EXTENSION_MS`,
  `extensionsUsed[pkg]++`, return true; otherwise false (no-op). Adds to the *deadline*, not to
  "now", so a tap at 3 s left yields 13 s, not 10.
- Everything else unchanged (I1). `isInGracePeriod` keeps working: the monitoring job in
  `GracePeriodService` re-reads the deadline every second, so no job restart is needed.

### BlockingSharedState
```
data class GraceWarning(
    val packageName: String,
    val deadlineWallMs: Long,
    val canExtend: Boolean,
    val dismissible: Boolean          // false for the final 5 s once the extension is spent
)
val graceWarning: StateFlow<GraceWarning?>            // written by GracePeriodService only
fun setGraceWarning(w: GraceWarning?)
fun extendGrace(pkg): Boolean = sessions.extendGrace(pkg)   // used by the pill's button
```
`graceWarning` is the *only* channel between the two services for this feature. `GracePeriodService`
decides *when*; `ChillpillAccessibilityService` decides *whether it is visible* (see below) and owns
the window, because only an accessibility service can add `TYPE_ACCESSIBILITY_OVERLAY` windows.

### GracePeriodService
The per-package loop in `startGraceTimer` changes from a boolean poll to:
```
while (true) {
    val remaining = BlockingSharedState.sessions.graceRemainingMs(pkg)
    if (remaining <= 0) break
    // In grace, so !canExtend means "extension already spent": that warning comes later and stays.
    val canExtend = sessions.canExtendGrace(pkg)
    val lead = if (canExtend) WARNING_LEAD_MS else FINAL_WARNING_LEAD_MS
    if (remaining <= lead && !reInterventionDisabled(pkg)) {
        val w = GraceWarning(pkg, deadline, canExtend, dismissible = canExtend)
        if (BlockingSharedState.graceWarning.value != w) BlockingSharedState.setGraceWarning(w)
    } else if (BlockingSharedState.graceWarning.value?.packageName == pkg) {
        BlockingSharedState.setGraceWarning(null)          // e.g. after +10 s pushed remaining > lead
    }
    delay(1000L)
}
BlockingSharedState.setGraceWarning(null)                  // always, before onGraceExpired
onGraceExpired(pkg)
```
- `reInterventionDisabledPackages` is read once when the job starts (it is a DataStore flow; the
  toggle cannot change mid-grace without going through Settings, and the worst case of a stale
  read is one unnecessary pill).
- `onGraceExpired` is unchanged (I1). Clearing the warning *before* it runs guarantees the pill is
  gone before `ReInterventionActivity` launches.
- Job cancellation (grace restarted for the same package) and `onDestroy` also clear the warning
  for that package (`finally`).
- Only one `GraceWarning` at a time. If two restricted apps are both in their last 10 s, the one
  in the foreground wins: the a11y service only shows the pill when
  `currentForegroundPackage == warning.packageName`, and each job only overwrites the flow when
  its package is the current foreground one, otherwise it leaves the flow alone.

### ChillpillAccessibilityService
- New `GraceWarningOverlayManager` (own file, `service/`), modelled on `SuggestionOverlayManager`
  (`OverlayComposeOwner` lifecycle, `ContextThemeWrapper`, `ChillpillTheme(applyWindowDecor = false)`)
  but with:
  - `WRAP_CONTENT × WRAP_CONTENT`, `gravity = TOP or CENTER_HORIZONTAL`, `y = statusBarInset + 8dp`
    (read the inset from `WindowInsets` on attach; fall back to 24 dp);
  - flags `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN or
    FLAG_HARDWARE_ACCELERATED`; type `TYPE_ACCESSIBILITY_OVERLAY`; `PixelFormat.TRANSLUCENT`;
  - root view `class GraceWarningView(ctx) : ComposeView(ctx)` (I3);
  - `show(warning)`, `update(warning)` (same window, new state — no remove/add flicker when
    `canExtend` flips), `dismiss()`; `isShowing` is **private** to the manager and never consulted
    by `processEvent` (I2).
- In `onServiceConnected`, collect `BlockingSharedState.graceWarning` on the main dispatcher and
  apply: `null` → dismiss; non-null and `currentForegroundPackage == pkg` → show/update; otherwise
  dismiss.
- Foreground change: at the end of the existing event handling (`processEvent`, and the overlay
  branch when it counts as a foreground change) call `graceWarningOverlay.dismissIfNot(pkg)`. The
  same-app branch and ignored SystemUI/keyboard windows leave it alone (shade over the app is not
  leaving). `ACTION_SCREEN_OFF` dismisses it. `onDestroy` dismisses it.
- Own-package branch of `onAccessibilityEvent`: `if (className == GraceWarningView::class.java.name) return`
  before touching `currentForegroundPackage` (I3).

### Pill UI (`ui/gracewarning/GraceWarningPill.kt`)
Visual demo: `specs/demos/grace-expiry-warning.html` (both states, light + dark).

- One row, 44 dp tall, `RoundedCornerShape(22.dp)`, `surfaceVariant` at 94 % alpha, 6 dp shadow,
  16 dp horizontal padding, max width 320 dp.
- Content: small timer icon · **"Time's up in 9 s"** (`labelLarge`, `onSurface`) · filled tonal
  button **"+10 s"** (`primary`) · × icon button (`onSurfaceVariant`).
- Countdown: `remaining = deadlineWallMs - System.currentTimeMillis()`, recomputed every second in
  a `LaunchedEffect(deadlineWallMs)`; shows `ceil(remaining / 1000)`. Deadline comes from the
  `GraceWarning`, so an extension re-keys the effect.
- State `canExtend = false` (extension already used): no button, text
  **"Time's up in 9 s"** only, × still present. Same layout, narrower.
- Tap "+10 s" → `BlockingSharedState.extendGrace(pkg)`; on `true` record usage event
  `GRACE_EXTENDED` (new constant in `UsageEventsRepository`; stats UI ignores unknown types).
  The pill then vanishes on the next service tick (remaining > lead) and returns at 10 s as a
  countdown-only pill.
- **Tap confirmation (added 2026-09-18 after device feedback).** The new deadline only reaches the
  pill on the grace service's next tick, up to a second later, during which the countdown carried on
  ticking down — the tap read as if it had missed. The button therefore answers locally and at once:
  a haptic tick, the row switches to a check icon and **"10 s added"** on `primaryContainer`, the
  button and × disappear, and the pill gives a short spring bounce (1.06×). The countdown is not
  shown again; the pill simply goes when the service clears the warning. Local state
  (`justExtended`, keyed on the deadline), so nothing about the invariants changes.
- State `dismissible = false` (the second warning, last 5 s): as above but with no × either — a
  countdown and nothing else. The user cannot take it away, but it still never takes focus or
  touches outside its own bounds.
- Tap × → `dismiss()` locally; the pill does **not** come back for this grace window unless the
  warning object changes (i.e. `canExtend` flips after an extension, or `dismissible` flips at the
  5 s mark). Implemented as `dismissedFor: GraceWarning?` in the manager.
- A11y: pill is `semantics { liveRegion = Polite }`, button has content description
  "Add ten seconds", × "Dismiss warning". Strings in `strings.xml`.
- Immersive / gesture-nav apps: the pill's y is computed from insets, not hard-coded. Games that
  draw under the status bar are the one case to eyeball on a device (task 12); adjust `y` if needed.

### Data / stats
- `UsageEventsRepository.GRACE_EXTENDED = "GRACE_EXTENDED"` — a string constant, no Room schema
  change, no DB version bump.

### Concurrency notes
- `SessionPolicy` stays `@Synchronized`; `extendGrace` is one synchronized call.
- `graceWarning` is a `MutableStateFlow` (thread-safe); written from the grace job
  (`Dispatchers.Default`), collected on `Dispatchers.Main.immediate` in the a11y service; window
  add/remove only on Main, as `SuggestionOverlayManager` already does.
- The a11y service's single-threaded `eventProcessorScope` is not used for the pill; dismiss calls
  from `processEvent` hop to Main via the manager (`withContext(Dispatchers.Main.immediate)`).

### Risks
- **R1** Pill emits a window-state event after all (some OEMs are noisy): mitigated by the class
  name filter (I3); confirm with `adb logcat -s ChillpillA11y`.
- **R2** Apps with `filterTouchesWhenObscured` (banking) may drop touches in the strip the pill
  covers. Acceptable: 10 s, top of the screen, user can ×.
- **R3** Two restricted apps in their last 10 s: only the foreground one shows a pill; the other
  gets no warning that round. Acceptable, rare.

## Implementation notes (2026-09-18)

Small deviations from the Design above, all deliberate:

- `SessionPolicy.graceDeadlineMs(pkg)` was added next to `graceRemainingMs`. The service publishes
  the *deadline* rather than `now + remaining`, so the `GraceWarning` object is identical from tick
  to tick and the flow only emits when something actually changed (extension used, package changed).
  Without it every second produced a slightly different deadline, re-keying the pill's countdown.
- The countdown string is a `<plurals>` (`grace_warning_countdown`), per convention 10 in
  `ARCHITECTURE.md`: counted text never uses `%d` in a plain string.
- The overlay manager's entry point is `applyWarning(warning, onExtend)` (show / update / dismiss in
  one call) plus `dismissIfNot(pkg)`, `dismiss()` and `dismissFromMainThread()`, rather than separate
  `show`/`update`. The accessibility service re-applies the current warning on a foreground change,
  so returning to the app mid-warning brings the pill back.
- `OverlayComposeOwner` is duplicated (privately) in `GraceWarningOverlayManager` rather than
  extracted from `SuggestionOverlayManager`, to keep the suggestion path untouched.
- The own-package branch of `onAccessibilityEvent` now ignores *every* own overlay window, not just
  the pill (see I3). This also changes the suggestion popup, which used to set
  `currentForegroundPackage = com.chillpill`; it is only ever shown over non-restricted apps, so no
  grace or blocking decision depended on that.
- `GracePeriodService`, the warning collector and the overlay manager log every publish, clear,
  update and dismiss, so a device run can be read straight from
  `adb logcat -s ChillpillA11y GracePeriodService GraceWarningOverlay`.

## Tasks

- [x] 1. `SessionPolicy`: `extensionsUsed`, `graceRemainingMs`, `canExtendGrace`, `extendGrace`,
        constants. Unit tests in `SessionPolicyTest`: extend once → deadline +10 s and `true`;
        second call → `false`, deadline unchanged; extend after expiry → `false`; `startSession`
        resets the count; `endSession`/`clearGrace` clear it; `graceRemainingMs` at 0 after expiry.
- [x] 2. `BlockingSharedState`: `GraceWarning`, `graceWarning` StateFlow, `setGraceWarning`,
        `extendGrace`.
- [x] 3. `GracePeriodService`: loop rewrite per Design; clear warning in `finally` and before
        `onGraceExpired`; read `reInterventionDisabledPackages` at job start.
- [x] 4. `GraceWarningView` + `GraceWarningOverlayManager` (`service/`), incl. `dismissedFor`.
- [x] 5. `GraceWarningPill` composable (`ui/gracewarning/`) + strings.
- [x] 5c. Tap confirmation on "+10 s" (haptic, check + "10 s added", bounce) and the matching
        demo state.
- [x] 5b. Amendment: `FINAL_WARNING_LEAD_MS` as the second warning's own lead time,
        `GraceWarning.dismissible`, the × hidden when `dismissible` is false, and that warning
        overriding an earlier ×.
- [x] 6. `ChillpillAccessibilityService`: collect `graceWarning`; dismiss on foreground change,
        screen off, destroy; own-package class-name filter.
- [x] 7. `UsageEventsRepository.GRACE_EXTENDED`; record on successful extension.
- [x] 8. `ARCHITECTURE.md`: §3 package map (new files), §6.1 (new field/methods), §6.2 (pill
        ownership + own-package filter), §6.3 (loop, warning channel), §11 strings.
- [ ] 9. Visual demo reviewed (`specs/demos/grace-expiry-warning.html`).
- [ ] 10. `./gradlew testDebugUnitTest lintDebug` green.
- [ ] 11. Device pass, `adb logcat -s ChillpillA11y GracePeriodService`, grace set to 1 min:
        (a) pill at 10 s, ignore → re-intervention at 0 s, pill gone before it appears;
        (b) tap +10 s at 4 s left → confirmation shows immediately, pill disappears, nothing at
            10 s, returns at 5 s without button or ×, re-block at 0;
        (c) tap × → no pill again this window, re-block at 0; but after an extension a × at 8 s is
            overridden by the second warning at 5 s (pill returns, no ×);
        (d) leave app at 6 s left → pill gone; return after expiry → regular block screen;
        (e) shade / keyboard open during pill → pill stays, app usable;
        (f) app with "Block again after grace" OFF → no pill;
        (g) fullscreen video app and a game drawing under the status bar → pill placement OK.
- [ ] 12. Confirm I3: no `onAccessibilityEvent ... pkg=com.chillpill` line when the pill appears
        (or that it is filtered by class name).
