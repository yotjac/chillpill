# Waiting-screen polish: picker position, ±1 min buttons, app icon

Status: IMPLEMENTED (tasks 1–5) — 2026-11-14. Tasks 6–7 (build/lint, device pass) are open.
Demo: `specs/demos/waiting-screen-polish.html`.

## Proposal

### Intent
Three small visual changes requested by Yotam (2026-11-14):

1. **Picker a little lower.** The time picker (`DurationPicker`) sits exactly in the centre of
   the space above the buttons, which reads as slightly too high. Move it down a little.
2. **−1 / +1 minute buttons.** A "−" button left of the slider and a "+" button right of it,
   each changing the picked length by one minute. Fine adjustment on a 1–90 slider is fiddly.
3. **The target app's icon** in a circle, centred near the top, on every waiting screen
   (`AppBlockScreen` — initial block / link picker — and `ReInterventionScreen`).

### Non-goals
- No change to the view model, the engine, the wait times, or the settings-confirmation wait
  (it has no target app).
- No new dependency (`material-icons-extended` is already in the build).

## Design

### 1. Picker offset
- `DurationPicker.kt` gets `val PickerTopOffset = 48.dp`. Both screens put
  `Modifier.padding(top = PickerTopOffset)` on the picker's slot (on `AppBlockScreen` this
  wraps the picker *and* the "limited" note so they move together). The slot stays vertically
  centred, so the picker's centre moves down by half of that: 24 dp.
- Only the picker moves; the attempts count and the re-intervention text stay where they are,
  and the buttons are untouched, so nothing jumps when the wait ends except the picker appearing
  (as today).

### 2. ± buttons (`DurationPicker`)
- The slider becomes a `Row`: `IconButton(Icons.Filled.Remove)` · `Slider(weight 1f)` ·
  `IconButton(Icons.Filled.Add)`. The chips row below is unchanged (full width).
- `−`: `minutes - 1`, disabled at 1. `+`: `minutes + 1`, disabled at the range max (30, 90, or
  the link limit).
- Nothing picked yet (`minutes == null`, PICK apps start empty — `specs/deliberate-choices.md`):
  both buttons set **1 min**, i.e. where the thumb is drawn. That counts as a deliberate pick,
  the same as dragging the slider.
- Each tap does the same `TextHandleMove` haptic as the slider and goes through
  `onMinutesChange`, so the view model's clamping applies.
- When the range is a single minute (link limit 1) the slider is hidden today; the buttons go
  with it.
- A11y: content descriptions "1 minute less" / "1 minute more" (new strings).

### 3. App icon
- New `ui/common/AppIconBadge.kt`: a 72 dp circle (`surface` colour, 6 dp shadow elevation)
  with the app's icon (`AppIcon`, 48 dp) centred in it.
- Both screens get a new `packageName: String` parameter, passed by their activities (they
  already read it from the intent).
- Drawn as the **last child of the screen's root `Box`**, `Alignment.TopCenter`, 48 dp from the
  top (the content's own top padding). So it is above the background image, the rising fill
  and the shine, is visible for the whole wait (it is outside `AppBlockScreen`'s clipped
  reveal), and takes no space in the content column — nothing else moves.
- The icon is decorative (`AppIcon` sets no description); the app name is already in the text.

### Visual demo
`specs/demos/waiting-screen-polish.html`: block screen during the wait, PICK picker after the
wait, and the re-intervention screen, all with the badge; the ± buttons work in the demo.

## Tasks
- [x] 1. `AppIconBadge` composable in `ui/common/`.
- [x] 2. `DurationPicker`: ± buttons around the slider, `PickerTopOffset`, new strings.
- [x] 3. `AppBlockScreen` + `ReInterventionScreen`: `packageName` param, badge, picker offset.
- [x] 4. `AppBlockActivity` / `ReInterventionActivity` pass `packageName`.
- [x] 5. `ARCHITECTURE.md`: new file in the package map, badge noted in §6.
- [ ] 6. `gradlew assembleDebug lintDebug` green.
- [ ] 7. Device pass: PICK app (empty start, ± from empty, ± at 1/30/90 bounds, need longer),
      link picker (limit 1 and > 1), Time limit / Pause only block, re-intervention; icon visible
      over a light and a dark background image.

## Follow-up (2026-11-14, later)
- Empty value reads "—" instead of "— min" (`pick_minutes_none`).
- The icon badge sits 80 dp from the top (was 48 dp).
- `DurationPicker` is framed: a 24 dp rounded card, `surfaceVariant` at 60 % with a 1 dp
  `outlineVariant` border, 12 dp side / 16 dp top padding, around title, value, slider row, chips
  and "need longer?". The link picker's "limited" note stays outside the card.
