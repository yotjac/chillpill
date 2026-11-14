# Link pass ("opened from a link")

Status: **IMPLEMENTED** (2026-11-13), pending Gradle build/lint and the device checklist (tasks
11–12, 16). Demo: `specs/demos/link-pass.html`.

Revision 2 (2026-11-13, Yotam): a link no longer lets the user straight in for a fixed time. It
shows a **link picker**: no wait, "how long do you need?" like Pick your time, with the setting as
the **maximum** (default 10 min). See "Revision 2" under Tasks.

Builds on `specs/session-engine.md` (all decisions stay in `EngineCore`) and
`specs/per-app-modes.md` (per-app `AppPolicy` / `AppRule`, rule snapshot per session).

## Proposal

### Problem

A friend sends an Instagram reel on WhatsApp. Tapping it opens Instagram, Chillpill shows the
full wait, and the user either sits through 10+ seconds to see a 15-second clip or gives up on
the message. The wait is meant for "I'm opening Instagram out of habit", not for "someone sent me
something". But skipping the wait outright would turn every link into a free door.

### Intent

1. A separate per-app setting, **"Opened from a link"**, off by default. When it is on and the
   app is opened *by another app* (a link, a share, an "open in app" button), Chillpill skips the
   wait and shows the **link picker**: the Pick your time slider and chips, ranging from 1 min to
   the app's **longest link visit** (default 10, setting range 1–90), no "need longer?". The
   picked visit is the **link pass**. Go home / Back on the picker just leave, as on any block.
2. When the link pass runs out while the user is still in the app, the app's **normal block
   screen** appears (its opening wait, then its mode as usual: Pause only / Time limit / Pick your
   time). So the pass only postpones the usual flow; it never replaces it.
3. Leaving and coming back during the pass is free (like any grace). After the pass, opening the
   app goes through the normal block.
4. **Repeat links are capped (L3, revision 4):** if a link visit of the app started (Continue on
   the picker) less than 15 min ago, the picker still shows but only goes up to **2 min** (or the
   app's limit if that is lower), with a short note: "you opened {app} from a link in the last 15
   min, so this one is up to 2 min". A picker left with Go home is not a visit. *(Revision 1–2
   had a hard "one per 15 min" that gave the normal block; revision 3 removed it.)*
5. Turning it on, or making the pass longer, is a loosening: Save goes through the confirmation
   wait.

### What counts as "opened from a link"

Android does not tell an accessibility service *why* an activity started, so Chillpill infers it
from what was in front just before:

- The restricted app's activity came up **directly over another app's activity** (presence was an
  `APP` that is not a home/launcher package, not the restricted app itself, screen on), **and**
- no SystemUI window (shade, heads-up) appeared after that app's **last activity window event**
  (a SystemUI window followed by more activity of the same app, like Pixel's back-gesture panel
  while moving around in WhatsApp, does not count; device trace `s7-link-from-whatsapp`), **and**
- it was seen through a window event (not repaired by a UsageStats probe).

Not a link: launcher icon, home screen widget, recents on a launcher-provided recents screen,
notification from the shade, coming back from our own block screen or main UI, a probe-detected
switch. All of these fail strict (normal block).

Known gaps (to check on device, task 12):

- **Quick-switch gesture** (swipe along the gesture bar from app A to the restricted app) may go
  straight from A to the app with no launcher window in between and so look like a link. It still
  only gets the picker (up to the app's limit), not a free pass.
- **Heads-up notification** taps may not produce a SystemUI window event; then a notification tap
  while in another app looks like a link.
- An app that opens the restricted app on its own (no user tap) also counts. Rare.

### Naming (user-facing)

Section on each app card under the mode fields: **"Opened from a link"**, switch **"Skip the
wait for links"**, description **"When another app opens {app} (a link or a share), skip the wait
and choose how long to stay, up to the limit below. After that, the usual wait."**, field **"Longest link visit (minutes)"**. Collapsed summary gets a suffix:
"· links ≤ 10 min". Picker title: **"a link opened {app}. how long do you need?"**, button
"open for N min" as in Pick your time.

### Non-goals

- No per-source-app allow list ("only links from WhatsApp").
- No global (all apps) switch: every setting is per app since per-app modes.
- No stats screen changes; the pass is recorded as a usage event only.

## Design

### Model

- `AppPolicy`: `linkPass: Boolean = false`, `linkPassMinutes: Int = 2` (1–30). Codec keys
  `linkPass`, `linkMinutes`; missing → defaults, so stored policies decode unchanged and
  `AppPolicy.DEFAULT` is unchanged (no override is written for untouched apps).
- `AppRule`: `linkPassMs: Long? = null` (`toRule()` sets it only when `linkPass` is on).
- `Input.Window` and `ProbeResult`: `home: Boolean = false`: the package is a home app
  (`ACTION_MAIN` + `CATEGORY_HOME` candidate). Primitives with defaults so old traces decode to
  `false` and replay unchanged (link pass is off in their configs anyway).
- `Presence`: `home: Boolean = false`.
- `AppState.Session`: `linkPass: Boolean = false`. A link-pass session is never "pause only"
  (it always ends in a block when it runs out in the app).
- `UsageKind.LINK_PASS` → `UsageEventType.LINK_PASS`.

### Engine rules

- **L1 (grant):** on an *entry* into restricted `pkg` from a window event, where the next step
  would be an initial block (E2: Idle, or E8: session over), if
  `rule.linkPassMs != null` and the previous presence qualifies (above): start
  `Session(graceDeadline = at + linkPassMs, rule, linkPass = true, leftAt = null)` and record
  `LINK_PASS`. No block, no `OPEN_ATTEMPT`.
- **L2 (expiry):** a link-pass session that runs out while the app is in front shows an
  `INITIAL` block (not re-intervention) and records `GRACE_EXPIRED_WHILE_ACTIVE`; away → Idle
  (G3, unchanged).
- The pass has the usual warnings (pill in the last 10 s, "+10 s" if the app allows it).
- `lastSystemUiAt`: time of the last SystemUI window (`com.android.systemui`) at or after
  `presence.since`. It disqualifies the next entry only if it is not older than the source app's
  latest activity window event (`presenceWindowAt`; `presence.since` is only the first one).

### Host

- `SessionEngine.isHomePackage(pkg)`: cached (5 min) set from
  `queryIntentActivities(MAIN + HOME)`; the manifest's `<queries>` gets that intent (package
  visibility). Set on `Input.Window` (accessibility service) and on probes.

### Settings

- `PolicyDraft`: `linkPass`, `linkMinutes` text, `PolicyField.LINK_MINUTES` (1–30). Not part of
  `fields(mode)`; the editor shows it in its own section for every mode.
- `isPolicyLooser`: off → on, or on with more minutes, is looser.
- `PolicyEditor`: divider, "Opened from a link" title, switch row + description, minutes field
  only when on. Pending colours like the other fields.

## Tasks

- [x] 1. Spec + demo.
- [x] 2. Model: `AppRule.linkPassMs`, `Input.Window.home`, `ProbeResult.home`, `Presence.home`,
      `Session.linkPass`, `UsageKind.LINK_PASS`.
- [x] 3. `EngineCore`: L1, L2, `lastSystemUiAt`, cooldown map.
- [x] 4. Engine tests (`LinkPassTest`): granted from another app; not from home, shade, own UI,
      probe, same app; cooldown; expiry in front → initial block; away → idle; return during pass;
      off by default; E8 path.
- [x] 5. `AppPolicy` fields + codec + `toRule`; `AppPolicyTest`.
- [x] 6. `PolicyDraft` + `isPolicyLooser`; `RestrictionReducingTest`.
- [x] 7. `PolicyEditor` section, `SettingsViewModel` handlers, `SettingsScreen` wiring, summary
      suffix, strings.
- [x] 8. Host: `isHomePackage`, accessibility service + probe, manifest `<queries>`,
      `UsageEventType.LINK_PASS`.
- [x] 9. `ARCHITECTURE.md`, `AGENTS.md` gotcha.
- [x] 10. JVM tests green.
- [ ] 11. `gradlew.bat testDebugUnitTest lintDebug assembleDebug` (Android/Compose code was not
      compiled in the implementing session: no Android SDK there).
- [ ] 12. Device checklist: link from WhatsApp → straight in, pill at the end, then the normal
      block; launcher icon → block; notification from the
      shade → block; heads-up tap and quick-switch gesture (record what happens; see "Known
      gaps"); turning it on asks for the confirmation wait.

### Revision 2: link picker (2026-11-13)

Model / engine changes on top of the above:

- `BlockKind.LINK`. L1 no longer starts a session: it sets `Blocking(LINK)` and emits
  `ShowBlock(pkg, LINK)` (no `OPEN_ATTEMPT`). Everything else about blocks (E3/E4 re-show keeps
  the kind, `BlockClosed` records `LEFT_APP`, a system-gesture close in front re-blocks with the
  normal INITIAL block) is unchanged.
- **A2b:** `ContinueTapped` on a `LINK` block starts `Session(linkPass = true)` for the picked
  length, rounded to whole minutes and clamped to 1 min … `rule.linkPassMs` (the full limit if
  nothing was picked, 1 min if link passes were turned off while the picker showed), records
  `LINK_PASS` (not `WAIT_COMPLETED`).
- `AppPolicy.DEFAULT_LINK_PASS_MINUTES` 2 → 10, `MAX_LINK_PASS_MINUTES` 30 → 90 (the picker's
  own limit). Stored values stay as they are.
- Host: `ShowBlock(LINK)` opens `AppBlockActivity` with `EXTRA_IS_LINK`. `AppBlockViewModel(isLink)`
  waits 0 s, starts the slider at min(5, limit), and limits it to the policy's
  `linkPassMinutes` (`pickLimit`); `DurationPicker(limit)` uses that range, chips from
  `linkQuickMinutes(limit)` (up to six "nice" values ending at the limit), hides "need longer?",
  and drops the slider for a 1-minute limit.

- [x] 13. Engine: `BlockKind.LINK`, L1 → picker, A2b; `LinkPassTest` rewritten, s7 replay expects
      the picker, fuzz checks link visit lengths.
- [x] 14. `AppPolicy` default 10 / max 90.
- [x] 15. Block screen: `EXTRA_IS_LINK`, view model, `DurationPicker(limit)`, title, strings;
      settings strings.
- [ ] 16. Device: link from WhatsApp → picker with no wait, max = setting; picked length is what
      you get; Go home on the picker → next link shows the picker again.

### Revision 3: no cooldown (2026-11-13)

- [x] 17. Removed the 15-minute limit (`lastLinkPassAt`, `LINK_PASS_COOLDOWN_MS`, its tests and
      the "Once every 15 min." text): every qualifying link shows the picker.

### Revision 4: repeat links capped at 2 min (2026-11-13)

- **L3:** `lastLinkPassAt[pkg]` (stamped on A2b, in memory, kept across sessions and config
  changes). On L1, a previous visit < `LINK_REPEAT_WINDOW_MS` (15 min) ago caps the picker at
  `LINK_REPEAT_MAX_MS` (2 min, or the rule's limit if lower). `Blocking(LINK)` and `ShowBlock`
  carry `linkMaxMs` and `linkLimited` (true only when the cap actually lowered the limit); E4
  re-shows keep them; A2b clamps to min(shown max, current rule limit).
- Host: `EXTRA_LINK_MAX_MINUTES`, `EXTRA_LINK_LIMITED`; the view model uses the engine's maximum
  (falls back to the policy) and the block screen shows `pick_link_limited` under the picker.

- [x] 18. Engine L3 + tests (`LinkPassTest`: capped at 2 min and says so, cap kept on re-show,
      full limit after 15 min, Go home is not a visit, limit < 2 min is not "limited", per app).
- [x] 19. Block screen extras, message, string.
- [ ] 20. Device: two links 9 min apart → second picker goes to 2 min with the note.

