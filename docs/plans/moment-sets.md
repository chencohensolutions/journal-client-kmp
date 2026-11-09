# Moment sets: viewer with a scrubber, flipping in the feed, making a moment (plan, Nov 9, 2026)

Picked in the UI lab round [moment-set](../ux-history/moment-set/2026-11-09-viewing-sets.md): options **b**, **c** and
**d**. Android first (shared logic with tests), then iOS from [ios-todo.md](../ios-todo.md) on the Mac.

## What the owner gets

1. **Opening a set** (a tap on it in Moments) opens the viewer on the set only, on its cover, not on the row.
   - Swipes **cross-fade in 0.12 s**, no slide, inside a set. (Swiping in the viewer elsewhere stays as it is.)
   - Under the photo, the **scrubber**: every photo of the set in one row; sliding a finger flips to the photo under
     it with a tick. A **grid** button at the top shows them all as squares (a sheet); a tap goes to one.
   - **☆ Highlight** · **▶ Play** · **Recompose** under the photo, in place of Write / Details / Share while in a set.
     Write, Details and Share stay in the top bar's menu (or the bars show both rows: decide on the device).
   - **Play**: the next photo every 0.8 s (Fast 0.35 s, Slow 2 s), cross-fading, round and round, bars hidden but a
     thin progress line, speeds and Stop. A tap stops. The screen stays on while playing.
   - **Recompose**: the crop screen's Moments crop for each photo, with the set's strip to go between photos without
     leaving, the shape chips (Original, Square, 4:5, Wide) and **Same frame for all** (every photo of the same shape
     gets this frame, so the set flips without jumping). Saved on Done as Moments crops, as today.
2. **Highlights** (asked Nov 9, 2026): any number of a set's photos can be marked **highlights**; those are what Moments
   shows of the set. The first highlight is the set's **cover** (where it opens, what the storyline and a small tile
   show). Until one is marked, the cover is the best-looking photo as today. For now Moments shows the highlights one
   under the other (as single photos), then the set's line "⧉ 36 · Hold to flip"; a layout per moment comes later,
   so the order of highlights is kept (the order they were marked, changeable in the grid sheet by dragging).
3. **In the feed** a set without highlights shows its cover (not a pager any more), with "⧉ 36" in the corner and a thin line with a
   mark where the photo shown is. **Hold and slide** flips through it in place (a tick per photo, 10 dp per photo);
   letting go keeps it there until the feed is left. A small **▶** plays it in place, muted, at 0.5 s.
   Long press is today's picking, so hold-and-slide starts only once the finger moves sideways after the hold.
4. **Make a moment** in the picking bar (two or more photos picked): a sheet with its **name**, the **highlights**
   and **Show it on**: its first day, its last day, or every day it has (each day shows its own photos with a link
   "Part of Beach weekend ›" that opens the set). Then the viewer opens on it. **Merge** stays for sets without a name.
      The sheet's "Main photo" row becomes **Highlights** (tap several). A named set shows its name above it in the feed ("Beach weekend · Fri – Sun").

## Shared logic (sharedLogic, with tests)

- **Highlights**: a new note field `highlight` (its place among the set's highlights: 1, 2, 3…; absent when not one),
  replacing `front` (read as highlight 1 when present, so notes written before keep their cover). Set only by
  ☆ Highlight (`setHighlight(itemIds, id, on)`, `moveHighlight(itemIds, id, to)`). Turning a set in the feed or the
  viewer is remembered on this device only (the `feed_page|` setting, as now). `MomentsFeed` gives a set block its
  highlights in order (`FeedBlock.Similar.highlights`), which the feed draws. Tests: marking, unmarking and
  reordering renumber the others; an old `front` becomes highlight 1; turning no longer writes notes.
- **Named sets**: a new note field `setInfo` on the set's cover: `{"name": "...", "showOn": "first"|"last"|"each"}`.
  Additive (note format 5), kept through `merged` like other fields, synced with notes as they are; the server stores
  notes whole, so no server change (to check in `media/note.go` that unknown fields are kept). `MomentsFeed` places a
  named set by `showOn`; with `each`, a day shows its photos plus a link entry. `makeMoment(itemIds, name, highlightIds, showOn)`
  in `StoriesController` = merge + highlights + setInfo. Tests in `MomentsFeedTest` / `StoriesControllerTest`.
- **Same frame for all**: `setMomentsCrop(itemIds, crop)` writes one Moments crop to many notes at once (one rebuild).
  Note today's rule "a cropped photo is never put in a set by itself" must not take a photo out of a set made by
  hand; it doesn't (hand sets win), but test it.
- **Viewer list for a set**: `LibraryRoutes` gets a viewer source "set" (the set's item ids), so the viewer swipes the
  set only and knows to show the set's controls.

## Android

- `MomentsFeedViews.kt`: the set block without `HorizontalPager`: one photo, the count badge, the position line, the
  hold-and-slide gesture (after `combinedClickable`'s long press stays for picking: start scrubbing on a horizontal
  move after 200 ms) and the small Play. Tap opens the viewer on the set.
- Viewer (`MediaViewerScreen` / `ViewerPager`): a set mode with `Crossfade` instead of the pager's slide, the
  `SetScrubber`, the grid sheet, the set's bottom bar, the slideshow (`LaunchedEffect`, `FLAG_KEEP_SCREEN_ON`).
- Crop screen: a set mode with the strip, shape chips and Same frame for all.
- Picking bar: **Make a moment** and its sheet (`MakeMomentSheet.kt`).
- Strings in `values/` and `values-iw/` (the lab's Hebrew is in `MomentSet.xcstrings`).
- The lab's parts (`MomentSetLab.kt`, `Lab/MomentSet/`) move in where they fit and the rest is deleted; `current` in
  `LabScreens` points at the real screens.

## iOS

An entry in `docs/ios-todo.md` with the same items, mirroring the Android files; the lab's SwiftUI parts
(`SetScrubber`, `SetSlideshow`, `SetRecompose`) are a head start.

## Docs

`docs/screens/moments.md` (sets in the feed, Make a moment), `viewer.md` (set mode), `crop.md` (set mode), the
history file's "promoted in" line.

## To decide on the device

- Whether Write / Details / Share stay visible in a set (two rows) or move to a menu.
- Whether the feed's hold-and-slide should start without the hold (a plain sideways drag would then not scroll the
  pager, which no longer exists, so a sideways drag could flip at once).

## Not now

Skip in slideshow, saving a slideshow as a video, a slow zoom between crops while playing.
