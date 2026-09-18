# Settings confirmation trigger + full-height wait overlay (bug fixes)

## Proposal

1. Save in Settings must show the "lower your standards" wait screen **only** when the draft
   (i) lowers the wait time, (ii) raises the grace period, or (iii) removes at least one
   restricted app. Any other edit saves immediately.
2. The rising overlay of a wait screen must reach the very top of the display. On a Pixel 8 it
   sometimes stopped right under the status-bar area.

## Design

**Confirmation trigger.** The rule moves into a pure top-level function
`isRestrictionReducing(...)` in `SettingsViewModel.kt`, covered by `RestrictionReducingTest`.
Two ways the old code could call a non-lowering edit "lowering" are closed:

- An emptied/invalid wait or grace field was reset on focus loss to the app defaults (12 s /
  5 min), not to the saved value. With a saved grace of 3 min, clearing the field and saving
  became "grace 3 -> 5". Save clears focus, so this happened on the Save tap itself. The
  fields now fall back to the saved values.
- Originals were read once in `init`. `onSaveClicked` now re-reads the persisted values first;
  restricted apps added meanwhile by the suggestion overlay are merged into the draft, so
  saving neither silently drops them nor counts them as a removal.

**Overlay height.**

- `AppBlockActivity` / `ReInterventionActivity` hide the status bar (`windowFullscreen`) but
  never opted into the display cutout, so the system could letterbox the window below the
  camera cutout. `Theme.Chillpill.Block` gets `windowLayoutInDisplayCutoutMode=shortEdges`
  (`values-v28`), and both activities set the same on the window.
- `SettingsConfirmationScreen` lives inside `MainActivity`'s `safeDrawing` padding, so it always
  stopped under the status bar. It now extends itself back out by the raw insets and pads its
  own text/buttons by them instead.

No UI design decisions change, so no visual demo.

## Tasks

- [x] Extract `isRestrictionReducing` + unit tests
- [x] Focus-loss fallback uses saved values
- [x] Re-read persisted values on Save; merge externally added apps
- [x] Cutout mode for block / re-intervention windows
- [x] Confirmation screen extends under system bars
- [ ] Manual check on device (Pixel 8): stricter-only edits save instantly; each of the three
      lowering edits shows the wait; overlay reaches the top on block, re-intervention and
      settings confirmation screens
