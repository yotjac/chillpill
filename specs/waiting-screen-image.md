# Waiting-screen image: user-selectable background

Status: PLAN ONLY — nothing implemented. Based on reading the working tree of 2026-09-17.

## Proposal

### Intent
The block ("waiting") screen always draws `R.drawable.block_activity_background`, loaded by
`rememberBlockBackgroundPainter()` in `ui/appblock/AppBlockScreen.kt`. Let the user choose that
image from Settings: either one of a few bundled images or a photo of their own.

### Decisions already taken (with Yotam, 2026-09-17)
- **Source: both.** A small bundled set plus "From gallery" for one custom photo.
- **Save model: draft until Save**, like every other field on the Settings screen. Changing the
  image is *not* restriction-reducing, so on its own it never triggers `SettingsConfirmationScreen`.

### Scope
- In: new setting, picker UI in `SettingsScreen`, image import/storage, block screen reads the
  setting, `ARCHITECTURE.md` update.
- Out: per-app images, cropping/positioning UI, multiple custom photos, image on the
  re-intervention or settings-confirmation screens (neither draws the image today; only
  `AppBlockScreen` does — confirm while implementing), any new dependency (no Coil/Glide).

### Open item for Yotam
- Supply the bundled images (suggest 3–5, portrait, ~1080×1920 JPEG, each under ~200 KB). Until
  they exist the feature ships with "Default" + "From gallery" and the list simply grows later.

## Design

### Data model
`data/settings/Settings.kt`:

```kotlin
sealed interface BlockBackground {
    data class BuiltIn(val id: String) : BlockBackground      // "default", "forest", ...
    data class Custom(val fileName: String) : BlockBackground // file in filesDir/backgrounds/
    companion object { val Default = BuiltIn("default") }
}
data class Settings(..., val blockBackground: BlockBackground = BlockBackground.Default)
```

`SettingsRepository`: one new key `stringPreferencesKey("block_background")`, encoded as
`builtin:<id>` or `custom:<fileName>`. Missing or unparseable value → `Default`, so existing
installs need no migration. Add `setBlockBackground()` and include it in `setSettings()`.

New `data/settings/BlockBackgrounds.kt`: the registry `id → @DrawableRes` for bundled images
(single source of truth for both the picker and the loader). Unknown id → default drawable, so
removing a bundled image in a later version is safe.

### Custom photo import
- Picker: `ActivityResultContracts.PickVisualMedia(ImageOnly)` via
  `rememberLauncherForActivityResult` in `SettingsScreen`. Already available in
  activity-compose 1.8.2; needs **no permission** and falls back to the document picker on old
  devices (minSdk 24).
- The returned URI grant is temporary, so the photo is copied right away (on `Dispatchers.IO`)
  by a new `data/settings/BlockBackgroundStore`:
  decode with `inSampleSize` + scale so the long side ≤ 2048 px, apply EXIF rotation, write
  JPEG q85 to `filesDir/backgrounds/custom_<timestamp>.jpg` via temp file + rename.
  A **new file name per import** means the block screen can never read a half-written file and
  any `remember(key)` cache invalidates naturally.
- Draft semantics: importing only creates the file and sets the *draft* to `Custom(fileName)`.
  The DataStore key is written in `saveChanges()`. Orphans (discarded drafts, replaced photos)
  are removed by `BlockBackgroundStore.cleanup(keep = persistedFileName)`, called after a
  successful save and once in `SettingsViewModel.init`.
- Import failure (unreadable URI, OOM) → snackbar/toast, draft unchanged.

### Block screen
Replace `rememberBlockBackgroundPainter()` with `rememberBlockBackgroundPainter(background)`:
- `BuiltIn` → current code path with the registry's drawable id.
- `Custom` → `BitmapFactory.decodeFile`; if the file is missing or decoding fails → default
  drawable; if that also fails → existing solid-surface fallback (keep the try/catch that
  protects against the REPLACED-package state).
- Already downscaled at import, so decoding stays in the tens of milliseconds. Load it with
  `produceState` on `Dispatchers.IO` and show the solid surface colour until it arrives — the
  block screen must appear instantly; the picture may land a frame later.
- `AppBlockViewModel` already reads `settingsRepository`; expose `blockBackground` from it and
  pass it into `AppBlockScreen` as a parameter (keeps the Composable stateless/previewable).
  No change in `service/`.

Side fix worth doing in the same change: move `block_activity_background.jpg` (and the new
bundled images) to `res/drawable-nodpi/`. In plain `drawable/` it is treated as mdpi and
upscaled 3–4× in memory on modern phones. Delete the unused `block_background.xml` placeholder.

### Settings UI
New section in `SettingsScreen`, between the two number fields and "Restricted apps":

```
Configuration
[ Wait time (seconds)    ]
[ Grace period (minutes) ]

Waiting screen image
┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐
│ ✓  │ │    │ │    │ │ my │ │ +  │   ← LazyRow, 9:16 thumbnails ~72×128 dp
│dflt│ │ #2 │ │ #3 │ │foto│ │    │
└────┘ └────┘ └────┘ └────┘ └────┘
                        From gallery
Restricted apps …
```

- Selected tile: 2 dp `primary` border + check badge. When the draft differs from the saved
  value, the border uses the same "pending" colour the number fields use (`waitPendingColors`),
  so the unsaved state reads consistently.
- "My photo" tile appears only when a custom file exists (draft or saved); tapping "+" again
  replaces it. Long-press on it → "Remove" (falls back to Default in the draft).
- Thumbnails: decode at tile size with `inSampleSize`, `remember`ed per item; no full-size
  bitmaps in the list.
- A11y: each tile is `selectable` with `Role.RadioButton` and a content description
  ("Default image", "Image 2", "Your photo", "Choose from gallery").
- Strings: follow the screen's current style (inline literals); don't start a strings.xml
  migration here.

Per AGENTS.md, a UI decision needs a visual demo: produce a small HTML mock of this section
(`frontend-design` skill) alongside the spec before implementation.

### SettingsViewModel
- Add `_originalBlockBackground` / `_blockBackground` flows, loaded in the existing settings
  `init` block.
- `onBuiltInSelected(id)`, `onCustomImagePicked(uri)` (runs the import, then sets draft),
  `onCustomImageRemoved()`.
- `hasChanges`: add the pair to the nested `combine` (one more inner `combine`, or collapse the
  drafts into a small `Draft` data class — preferable, the nest is already hard to read).
- `isRestrictionReducing()`: unchanged. `saveChanges()`: persist, update original, run cleanup.

### Risks / notes
- Backup: if `android:allowBackup` is on, `filesDir/backgrounds/` is backed up with the
  DataStore key, so a restore stays consistent. If a restore ever brings the key without the
  file, the missing-file fallback covers it.
- Very large/odd images (HEIC, 100 MP panoramas): two-pass decode with `inJustDecodeBounds`
  keeps memory bounded; HEIC decodes natively on API 28+, fails gracefully below.
- Text legibility over arbitrary photos: the overlay that creeps up is 88 % surface, and the
  text sits on it, so no extra scrim is planned. Check on device with a very bright photo.

## Tasks
- [ ] HTML mock of the "Waiting screen image" section; review with Yotam
- [ ] `BlockBackground` model + `Settings` field
- [ ] `SettingsRepository`: `block_background` key, parse/encode, `setBlockBackground`, `setSettings`
- [ ] `BlockBackgrounds` registry; move images to `drawable-nodpi`, delete `block_background.xml`
- [ ] `BlockBackgroundStore`: import (downscale, EXIF, atomic write), `cleanup(keep)`, `fileFor(name)`
- [ ] `AppBlockViewModel` exposes `blockBackground`; `AppBlockActivity` passes it down
- [ ] `AppBlockScreen`: parameterised async loader with fallback chain
- [ ] `SettingsViewModel`: draft/original flows, handlers, `hasChanges`, save + cleanup
- [ ] `SettingsScreen`: thumbnail row, Photo Picker launcher, pending highlight, a11y semantics
- [ ] Add bundled images when supplied
- [ ] `ARCHITECTURE.md`: new DataStore key, new files, `filesDir/backgrounds/`
- [ ] `gradlew.bat assembleDebug lintDebug`
- [ ] Manual check on device: pick built-in → Save → block screen shows it; pick photo → leave
      without saving → old image still used and orphan removed on next Settings open; pick photo →
      Save → shown; delete the file via adb → default shown, no crash; image-only change does not
      open the confirmation wait; image change + shorter wait time still does
