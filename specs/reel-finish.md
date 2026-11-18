# Reel finish ("let the current reel finish")

Status: **IMPLEMENTED** (2026-11-18), engine and policy code covered by JVM tests (189 pass).
Yotam built and ran it on his Pixel the same day (the version with the 60 s cap and the
confirmation wait, both changed since) and said it looked good; the checklist items in task 10
have not been reported one by one, and task 11 is open. Background and the device
recording behind the view ids: Project doc `claude/instagram-reel-end-probe.md`.

Builds on `specs/session-engine.md` (all decisions stay in `EngineCore`), `specs/per-app-modes.md`
(per-app `AppPolicy` / `AppRule`, rule snapshot per session) and `specs/grace-expiry-warning.md`
(the pill).

## Proposal

### Problem

Time runs out on Instagram in the middle of a reel. The re-intervention cuts it off a few seconds
before the punchline, which feels arbitrary and makes "Keep using" more tempting than it should
be. The natural stopping point is the end of the reel, or the moment the user reaches for the
next one.

### Intent

1. A per-app switch, **"Let the current reel finish"**, off by default, offered only for apps
   whose reel viewer Chillpill can follow (today: Instagram) and only for Time limit / Pick your
   time.
2. When it is on and time runs out while a reel is playing that ends **within 90 s**, the block
   waits. It then comes at whichever is first: the reel's end, or the user's **swipe to the next
   reel**.
3. In every other case the block comes on time, as today: no reel on screen, a reel that is
   paused, a reel with more than 90 s left, a reel without a progress bar, or anything Chillpill
   cannot read with certainty. The feature only ever acts on positive evidence.
4. While it waits, the pill says so: "Time's up after this reel · N s". No "+10 s" during the
   wait (the wait is the extra time).
5. Once per session. The wait is fixed when it starts: scrubbing back or pausing does not extend it.
6. Turning the switch on or off saves without the confirmation wait (Yotam, 2026-11-18): it moves
   where a visit stops by at most 90 s, it does not open the door wider.

### Non-goals

- Other apps (TikTok, YouTube Shorts). The engine part is generic; each app needs its own
  recording and view ids in `ReelWatcher`.
- Reels opened in other Instagram surfaces if they use another pager (not recorded yet).
- Knowing that a reel *ended* by anything other than its reported length (no loop detection).

### Cost: the accessibility service can now read window content

`canRetrieveWindowContent` goes from `false` to `true` for every build. Android shows "View and
control screen" when the service is enabled, and Play's accessibility policy wants the use
disclosed. The service description string now says what is read and when. Chillpill reads exactly
two things, only for an app with the switch on, only in the last 15 s of its time and during the
wait: the seek bar's range and the id of the view that scrolled.

## Design

### What the recording showed (Instagram, 2026-11-18, Pixel)

- `clips_viewer_view_pager` (`androidx.viewpager.widget.ViewPager`) sends `TYPE_VIEW_SCROLLED` on
  every swipe, about five events in half a second; `fromIndex` steps by one when the next reel
  takes over. No other scrolling (feed, comments, DMs) comes from that view.
- `scrubber` (`android.widget.SeekBar`) has `rangeInfo` in milliseconds: position in 0..length.
  It advances ~1000 per second while the reel plays, also under the comments sheet.
- Some reels have no scrubber. On other tabs the pager and scrubber stay in the node tree, frozen.

### Model

- `AppPolicy.finishReel` (JSON `finishReel`, default false) → `AppRule.finishReel`
  (`toRule()`: false for PAUSE_ONLY; `PolicyResolver.config`: false unless the package is in
  `ReelApps.PACKAGES`).
- `Session.reelSeenAt`, `Session.reelEndsAt`: the last "a reel is playing, it ends then" report.
  `Session.reelHeld`: the deadline was moved to the reel's end.
- Inputs `ReelProgress(pkg, remainingMs, at)` and `ReelSwiped(pkg, at)` (both in the trace codec).
- `WarningUi.reelHold`.
- Constants: `REEL_HOLD_MAX_MS` 90 s (60 s until Yotam raised it, 2026-11-18), `REEL_FRESH_MS` 2.5 s, `REEL_END_SLACK_MS` 0.5 s,
  `REEL_WATCH_LEAD_MS` 15 s.

### Engine rules

- **R1 (report):** `ReelProgress` for the app in front, whose session has `rule.finishReel`, is not
  PAUSE_ONLY, not past its grace and not held, stores `reelSeenAt = at`,
  `reelEndsAt = at + remainingMs`. Anything else is ignored.
- **R2 (hold):** when a session's deadline is processed with the user in the app (G2 / L2), and
  `rule.finishReel`, not yet held, `deadline - reelSeenAt <= REEL_FRESH_MS` and
  `0 < reelEndsAt - deadline <= REEL_HOLD_MAX_MS`: instead of blocking,
  `graceDeadline = reelEndsAt + REEL_END_SLACK_MS`, `reelHeld = true`. That deadline then expires
  like any other (re-intervention, or the initial block for a link pass; "while away" if the user
  left). Held sessions ignore `ReelProgress` and `ExtendTapped`. This is the one exception to I5
  ("a deadline only moves on Continue / +10 s").
- **R3 (swipe):** `ReelSwiped` for the app in front: held → the session expires now (same path as
  the deadline); not held → `reelSeenAt` / `reelEndsAt` are cleared, so a deadline right after a
  swipe blocks on time until the next reel has been seen playing.
- **R4 (watch window):** `reelWatch(now)` names the app in front when its session has
  `rule.finishReel` and is held or within `REEL_WATCH_LEAD_MS` of its deadline; else null.
  `nextWakeUp` includes `deadline - REEL_WATCH_LEAD_MS` for such sessions.
- **Pill:** a held session shows `WarningUi(reelHold = true, canExtend = false, dismissible = false)`
  for the whole wait.

### Host

- `SessionEngine.reelWatch: StateFlow<String?>` = `core.reelWatch(now)` after every input.
- `service/ReelWatcher` (main thread, owned by the accessibility service) is a sensor with no say:
  while `reelWatch` names an app with known view ids it
  - reads the seek bar once a second (`rootInActiveWindow.findAccessibilityNodeInfosByViewId`,
    visible node, `rangeInfo`); two readings with the same length, at most 2.5 s apart, that moved
    forward at 0.25–4 units per ms → `ReelProgress(remaining / rate)`;
  - turns a pager scroll event whose `fromIndex` changed into `ReelSwiped`.
- `ChillpillAccessibilityService` adds `TYPE_VIEW_SCROLLED` and the flags
  `FLAG_REPORT_VIEW_IDS | FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` to its `serviceInfo` only while the
  watcher is active; otherwise it gets window-state events only, as before.
- `res/xml/accessibility_service_config.xml`: `canRetrieveWindowContent="true"`.

### Settings

`PolicyEditor` shows the switch under "+10 s" when `pkg in ReelApps.PACKAGES` and the mode is not
Pause only. `isPolicyLooser` ignores it: no confirmation wait either way.

### Failure modes (all fail strict: block on time)

Instagram renames a view id or changes the seek bar's unit; the seek bar is hidden or missing; the
reel is paused; `rootInActiveWindow` is another window; the service lost the capability. A swipe
missed during a hold costs at most the rest of that reel (≤ 90 s).

## Tasks

1. [x] Model: `AppRule.finishReel`, `ReelApps`, `Session` fields, inputs, `WarningUi.reelHold`, trace codec.
2. [x] `EngineCore`: R1–R4, pill, no extension while held, wake-up.
3. [x] Tests: `ReelFinishTest` (22 cases), fuzz generates reel inputs and reports before deadlines
   and checks the hold bounds; `ReelFinishPolicyTest`.
4. [x] `AppPolicy` / codec / `PolicyResolver.config` gate / `PolicyDraft` / `isPolicyLooser`.
5. [x] `SessionEngine.reelWatch`, `ReelWatcher`, service hooks, dynamic `serviceInfo`.
6. [x] Settings switch, strings, pill text, service description.
7. [x] `canRetrieveWindowContent="true"`; temporary probe (`ScrollProbe`, debug XML) removed.
8. [x] ARCHITECTURE.md, AGENTS.md.
9. [ ] `gradlew.bat testDebugUnitTest lintDebug assembleDebug` (Android / Compose code has not been compiled).
10. [ ] Device checklist:
    - After installing, turn the accessibility service off and on if the switch has no effect
      (the new capability).
    - Instagram on Time limit 1 min, switch on. Reel with > 15 s left at the deadline and ≤ 90 s:
      pill "Time's up after this reel", block at its end.
    - Same, but swipe during the wait: block at the swipe.
    - Reel with minutes left: block on time. Feed / DMs / paused reel: block on time.
    - Comments open on a playing reel at the deadline: waits (the reel plays underneath).
    - `adb logcat -s ReelWatcher SessionEngine`: "watching com.instagram.android" appears 15 s
      before the deadline and `ReelProgress` inputs reach the engine while the pill is showing
      (checks that the pill's overlay window does not take over `rootInActiveWindow`).
    - Switch off: no scroll events, no reads (no "watching" line).
    - Watch for lag in Instagram. Yotam saw some once, well before the pill, and it did not come
      back. If it does after a session's first watch window: the first node read may turn on the
      platform's accessibility cache for the service (`mUsesAccessibilityCache`), which makes
      every app send it cache-critical events until the service restarts. Ways out: disable the
      cache (API 33+, unverified), or drop the seek-bar read and keep swipe detection only.
11. [ ] Open questions from the recording: a reel watched to its end (loop), reels opened from
    the feed / a profile / a DM, whether `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` is needed.
