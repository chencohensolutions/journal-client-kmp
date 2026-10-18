# Story

One story: a trip, a birthday, any moment worth naming. Opened by tapping a story card on the Journal, and right
after creating a story.

## Layout

- **Top bar:** back, the Offline pill when the server can't be reached, and **⋯** with **Rename**,
  **Arrange layout** and **Delete story**.
- **Header:** the first day as a calendar block (OCT / 8), the title, and a summary such as
  "Oct 8 – 16 · 9 days · 142 photos · 2 groups". One-day stories leave out the day count.
- **Story:** the story's opening text from story.md, in a serif, with **Edit**. Empty stories prompt "Write about
  this story…".
- **Neighboring days:** buttons such as **+ Add Tue, Oct 7 · 6 photos** for unsorted days up to two days before
  or after the story. One tap adds that day's unsorted photos, so a trip grows day by day.
- **Burst suggestions:** one card per burst of similar photos, with **Group them** and **Not now**. See
  [group.md](group.md).
- **Notes**, when they apply: "N photos of this story aren't on this device" (deleted, or hidden by limited access),
  or "None of this story's photos are on this device".
- **Photos and groups, day by day, oldest first**, so the story reads like a story. Each day is a grid six square
  cells wide. A [group](group.md) is one tile where its first photo would be. Unless the story was arranged, every
  tile is **Original**: full width at its photo's own aspect ratio (a group's cover), one under the other, so nothing
  is cropped. In stories longer than a day each day is labeled
  "Day 2 · Fri, Oct 9".

Tapping a photo opens the [viewer](viewer.md), swiping through this story's photos, with each group as one stop
(its cover). From there a photo can be removed from the story or added to others.

**Long-press** a photo or a group to select: the top bar shows "4 selected" with ✕, tiles get check circles, and the
bottom bar offers **Group** (two or more tiles, into a new group; selected groups become its subgroups), **Merge**
(when a group is among them: everything joins the first selected group) and **Remove** (the photos, and the photos of
selected groups, from the story). See [group.md](group.md).

**⋯ › Arrange layout** switches to arrange mode: the top bar shows **Arrange** with **Reset** and **Done**, the
story's text, neighboring days and suggestions are hidden, and tapping a photo or group picks it (orange outline;
tap again to drop it). The bottom panel then offers:

- **Sizes**, each drawn as its shape on the grid: **Auto** (the automatic size above, drawn as what it resolves to),
  **Small** 2×2, **Wide** 4×2, **Tall** 2×4, **Half** 3×3, **Large** 4×4, **Full** 6×4 cells, and **Original**: full
  width at the photo's own aspect ratio, so nothing is cropped. An Original tile stands on its own below the tiles
  before it; tiles after it continue under it.
- **Earlier / Later**: moves the tile one place among its day's tiles. Tiles never move to another day.
- **Focus**: the whole photo (a group's cover), full screen, with a frame around what the tile will show. Tap the
  part that must stay in view, and **Zoom** (1×–4×) to show it closer; the tile as it will look is below. Zoom
  works with every size, Original included. Changing a group's cover resets its focus and zoom.

Tiles are placed in order, each at the first spot from the top where it fits, so a later small tile fills a hole a
large one left (dense packing); a hole stays empty only when nothing after it fits. Every change is saved right
away. **Reset** (after a confirmation) puts sizes, order and focus back to automatic. Photos added after arranging
fall in by time, right after the last tile taken no later than them. The group page keeps its automatic grid.

## Actions

| Action | Result |
|---|---|
| Rename | Title dialog; blank titles can't be saved |
| Delete story | Confirmation; deletes the story file only. Its photos go back to unsorted and stay on the device |
| + Add day | Adds that day's unsorted photos (trip-like stories only) |
| Edit story | Full-screen editor; saved to story.md |
| Group them / Group / Merge | Makes a group (nesting selected groups) or merges into one; "Grouped N photos · Undo" for a few seconds |
| Not now | Hides that burst suggestion for good |
| Remove (selection) | Takes the photos out of the story and its groups |
| Arrange: size, Earlier/Later, Focus | Saved to story.json at once; a photo that joins a group loses its own size and focus |
| Arrange: Reset | Confirmation; back to the automatic layout |

## No dates of their own

A story is a title over photos, not a date range: it can be a trip ("Japan trip") or span years ("Our dog"). What
the app shows about time comes from its photos:

- the calendar block is its **oldest photo's day**, and the summary shows the span of its photos;
- "N days" and the "Day 2 · Fri, Oct 9" labels show only for **trip-like** stories, whose photos span at most 14 days
  (`SHORT_SPAN_DAYS`); so do the **+ Add <day>** buttons;
- on the Journal, a story sits at its **newest photo's day** (or the day it was created, if none of its photos is on
  this device), so adding photos to an old story moves it up.

## Where stories are kept

Stories exist only on this device until uploads and sync are designed. Each is a folder with `story.json` and
`story.md` in the app's private storage (Android `files/journal/stories/<id>/`, iOS
`Application Support/journal/stories/<id>/`).

Stories created before the rename lived in `events/<id>/event.json`; the app moves them to `stories/<id>/story.json`
on first launch, and their old dates are ignored.

**Ids and folder names** are the UTC creation time, `2026-10-17T12-52-22.257Z` (dashes instead of colons so it is
a valid folder name everywhere), and **never change**: renaming or redating a story only edits story.json. Each
new id is at least 1 ms after the last one the device issued (kept in settings), so ids never repeat on a device,
even if the clock goes back. Two devices creating stories in the same millisecond is left to sync: the server will
refuse to overwrite an id with different content. Stories created before this change keep their random ids.

**story.json** follows the agreed server format (version 1), except that photos are referenced by device id:

```json
{
  "v": 1,
  "id": "2026-10-17T12-52-22.257Z",
  "title": "Walk with the dog",
  "createdAt": "2026-10-17T12:52:22Z",
  "updatedAt": "2026-10-17T12:52:22Z",
  "tiles": [
    { "type": "media", "deviceId": "49", "fileName": "20261008_190157.heic", "takenAt": 1791475317937 },
    { "type": "media", "deviceId": "44", "fileName": "20261008_182059.mp4", "takenAt": 1791472871000,
      "size": "wide", "focus": { "x": 0.66, "y": 0.2 } }
  ]
}
```

**Tiles are listed in the story's order**, oldest first unless arranged. A tile may carry `size` (`small`, `wide`,
`tall`, `half`, `large`, `full`, `original`; automatic when missing), `focus` (0–1 from the left and from the top of
the photo; the center when missing) and `zoom` (above 1, up to 4; none when missing). All are optional, so older files
read as automatic layouts and older readers ignore them.

Groups are `group` tiles holding `media` tiles; see [group.md](group.md). `dismissedSuggestions` lists burst
suggestions answered "Not now".

**story.md** holds the text: a `# Title` heading (rewritten from story.json on every save), the story's opening
text, then one section per group under `<!-- group:<id> -->`. Editing it outside the app works; a section whose
group is gone is kept so no text is lost.

`deviceId` is the MediaStore id on Android and the PHAsset local identifier on iOS. `fileName` and `takenAt` are
kept so each photo can be matched to its server copy (by content hash) once uploads exist. Saves write a temporary
file and rename it over the old one; a damaged file is skipped without hiding other stories.

Uninstalling the app deletes these files.

## Not built yet

Sub-stories, layout templates, drag-and-drop reordering, arranging on iOS (iOS still shows the automatic layout,
see below), choosing the story's cover, and grouping by visual similarity (today bursts are found by time and the camera's burst id only).

## Code

- Shared: [stories/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/)
  (`StoriesController`, `FileStoryStore`)
- Android: [StoryScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/StoryScreen.kt),
  cards, dialogs and the Add to story sheet in
  [StoryComponents.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/StoryComponents.kt),
  the grid in [GroupComponents.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/GroupComponents.kt)
  (`storyGrids`), arrange mode in
  [ArrangeComponents.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/ArrangeComponents.kt)
- Layout logic (sizes, packing into bands, order): [StoryLayout.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoryLayout.kt)
- iOS: [StoryPageView.swift](../../iosApp/iosApp/StoryPageView.swift),
  [StoryComponents.swift](../../iosApp/iosApp/StoryComponents.swift)
