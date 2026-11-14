# Deliberate choices: delayed "Never ask me again", no preselected visit length

Status: IMPLEMENTED (tasks 1–5) — 2026-11-14. Tasks 6–7 (build/lint, device pass) are open.

## Proposal

### Intent
Two small frictions, both so a choice is made on purpose instead of by reflex:

1. **Suggestion popup:** "Never ask me again about {app}" is the one answer that can't be taken
   back from the popup. It starts disabled with a small round progress ring inside it that fills
   over **4 s**; only when the ring is full does the button become tappable. "Add to restricted"
   and "Not now" work immediately.
2. **Pick your time apps:** the duration picker opens with **no length selected**. The "Open"
   button is disabled (it reads *"pick a time"*) until the user moves the slider or taps a quick
   chip. This holds on the opening block and on the time's-up screen.

### Non-goals
- The link picker (`specs/link-pass.md`) keeps its preselected value: it applies to every app
  opened from a link, not only to Pick your time apps.
- No engine change. `ContinueTapped.chosenMs` is only sent once a length exists; the engine's
  5-min fallback for a missing `chosenMs` stays for old traces / suggestion RESTRICT.

## Design

### Suggestion popup (`ui/suggestion/SuggestRestrictionScreen.kt`)
- `Animatable(0f)` animated to 1f over `NEVER_ASK_DELAY_MS = 4000` (linear) in a
  `LaunchedEffect(Unit)`, so it restarts with every new popup.
- The `TextButton` is `enabled = progress == 1f`. While not ready it shows a determinate
  `CircularProgressIndicator` (16 dp, 2 dp stroke) left of its label; when full the ring is
  removed and the label is the plain button. The disabled label uses the normal disabled colour.

### Picker (`ui/appblock/AppBlockViewModel.kt`, `DurationPicker.kt`, both block screens)
- `pickMinutes: StateFlow<Int?>`; `null` = nothing chosen. PICK apps start at `null`; the link
  picker starts at `min(5, limit)` as before. The range toggle keeps `null` as `null`.
- `DurationPicker(minutes: Int?)`: the big value shows *"— min"*, the slider thumb rests at 1
  and no chip is selected. Any slider move or chip tap sets the value (also a tap on 1).
- The Open button: `enabled = <phase ready> && minutes != null`, label *"pick a time"* while
  `null`, *"open for N min"* after. `onContinue()` ignores a tap with no length (defensive).

### Visual demo
`specs/demos/deliberate-choices.html`.

## Tasks
- [x] 1. Spec + demo.
- [x] 2. Suggestion popup: 4-s ring, button disabled until full.
- [x] 3. View model: nullable picked length, PICK starts empty, link picker unchanged.
- [x] 4. `DurationPicker` + `AppBlockScreen` + `ReInterventionScreen`: empty state, Open disabled.
- [x] 5. Strings (`pick_minutes_none`, `pick_choose_first`); amend `specs/per-app-modes.md`.
- [ ] 6. `./gradlew assembleDebug lintDebug testDebugUnitTest`.
- [ ] 7. Device: popup ring fills in 4 s and only then accepts the tap; Pick app block and
      time's-up screens open with nothing chosen and Open disabled until a pick; link picker
      unchanged.
