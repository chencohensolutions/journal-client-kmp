# Crop

Crops are **only for looking**: the photo itself is never changed. Each photo has one crop, its **Moments** crop, any
shape between 3:1 and 1:3, saved with its note: Moments, the storyline and the [viewer](viewer.md) show the photo at
it. A photo without a crop shows whole. Videos aren't cropped (yet). A cropped photo leaves its near-duplicate set in
Moments and shows on its own (unless merged into a set by hand).

Until Nov 10, 2026 a single photo had a crop screen of its own with three crops (**Moments**, **Portrait** and
**Landscape**, the last two at the screen's shape for the viewer). On Android it is gone (asked Nov 10, 2026: one crop
screen, the set's): one photo opens the same screen as a set's Recompose, alone. Portrait and landscape crops already
saved stay in the notes (`PhotoCrops`) but nothing shows or edits them on Android; iOS still has the old screen
([ios-todo.md](../ios-todo.md)).

## Opening it

- Moments: long press a photo → **Crop**; the viewer of a single photo: **⌗** in its floating bar (Android:
  `Route.Recompose` with a list of one id). A set: **Recompose** in its viewer.

## One photo

The screen below with only that photo: titled **Crop**, the hint "Any shape. This is how the photo shows in Moments.",
no strip and no Same frame for all.

## A set: Recompose (Nov 9, 2026)

Android only; iOS not built yet ([ios-todo.md](../ios-todo.md)). From **Recompose** in the [viewer's set
mode](viewer.md#a-set-nov-9-2026) (`Route.Recompose`, `SetCropEditor`): the Moments crops of every photo of the set
(videos left out), so it flips in Moments without jumping.

- The photo shown with its frame, as in the Moments mode (drag, pinch, corners and sides); **Reset** and **Done** at
  the top. Done saves every photo's crop at once (`StoriesController.setMomentsCrops`, one rebuild), or just closes
  when nothing changed.
- **Shape chips**: Original (no crop) · Square · 4:5 · Wide (16:9): the largest frame of that shape around the
  current one; the frame stays free after.
- **The set's strip**: its photos in a row, the one shown outlined, a dot on each cropped; a tap goes to it without
  leaving.
- **⧉ Same frame for all**: every photo of the set of the same shape as the one shown gets its frame.
- A near-duplicate set that wasn't made by hand becomes one when Recompose saves (a cropped photo is never put in a set
  by itself), so it stays together.

## In the viewer

The photo fills the screen at its crop, with **Cropped · Show all** at the top; **Show all** opens out to the whole photo (a 0.3 s glide, Nov 9, 2026)
(kept while swiping) and **Show crop** turns it back. The chip shows and hides with the viewer's bars.
In a set (its Moments crops) the chip works the same way. A crop made on a phone with another screen shape keeps its
center and height (width sideways), and the other side follows this screen.

## How it's saved

In the photo's note file, `media/<device id>.json` (see [tags.md](tags.md)), format v2:

```json
"crops": {
  "moments":  { "x": 0.0, "y": 0.18, "w": 1.0, "h": 0.62 },
  "portrait": { "x": 0.4, "y": 0.0, "w": 0.35, "h": 1.0, "aspect": 0.462 }
}
```

Fractions of the upright photo, so they fit any resolution and a server copy; `aspect` is the screen shape a viewer
crop was made for. A note with only crops is kept; a crop alone doesn't put a photo on the storyline. Saving keeps
fields of the file this version doesn't know, also inside `"crops"`, so an older app never drops a newer one's.

## Code

- Shared: [PhotoCrops.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/PhotoCrops.kt)
  (moving, pinching, corners, fitting another screen; tested in `CropsTest`), saved by
  [PhotoNote.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/PhotoNote.kt),
  `StoriesController.setCrops`.
- Android: [CropScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/CropScreen.kt).
- iOS: [CropView.swift](../../iosApp/iosApp/CropView.swift): the same screen and rules, calling the shared geometry;
  any screen opens it with `.cropCover($request, stories:save:)` and a `CropRequest(itemId:kind:)`. The photo
  geometry stays left to right in Hebrew. Shown in the viewer by
  [MediaViewerView.swift](../../iosApp/iosApp/MediaViewerView.swift).
