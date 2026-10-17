# Story

One story: a trip, a birthday, any moment worth naming. Opened by tapping a story card on the Journal, and right
after creating a story.

## Layout

- **Top bar:** back, the Offline pill when the server can't be reached, and **⋯** with **Rename** and
  **Delete story**.
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
- **Photos and groups, day by day, oldest first**, so the story reads like a story. A [group](group.md) is a large
  tile where its first photo would be; the first tile of a day with three or more tiles is large too; the rest fill
  rows of three. In stories longer than a day each day is labeled "Day 2 · Fri, Oct 9".

Tapping a photo opens the [viewer](viewer.md), swiping through this story's photos, with each group as one stop
(its cover). From there a photo can be removed from the story or added to others.

**Long-press** a photo to select: the top bar shows "4 selected" with ✕, tiles get check circles, and the bottom
bar offers **Group** (two or more) and **Remove** (from the story).

## Actions

| Action | Result |
|---|---|
| Rename | Title dialog; blank titles can't be saved |
| Delete story | Confirmation; deletes the story file only. Its photos go back to unsorted and stay on the device |
| + Add day | Adds that day's unsorted photos (trip-like stories only) |
| Edit story | Full-screen editor; saved to story.md |
| Group them / Group | Makes a group; "Grouped N photos · Undo" for a few seconds |
| Not now | Hides that burst suggestion for good |
| Remove (selection) | Takes the photos out of the story and its groups |

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
    { "type": "media", "deviceId": "49", "fileName": "20261008_190157.heic", "takenAt": 1791475317937 }
  ]
}
```

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

Sub-stories, arranging the layout, choosing the story's cover, and grouping by visual
similarity (today bursts are found by time and the camera's burst id only).

## Code

- Shared: [stories/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/)
  (`StoriesController`, `FileStoryStore`)
- Android: [StoryScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/StoryScreen.kt),
  cards, dialogs and the Add to story sheet in
  [StoryComponents.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/StoryComponents.kt)
- iOS: [StoryPageView.swift](../../iosApp/iosApp/StoryPageView.swift),
  [StoryComponents.swift](../../iosApp/iosApp/StoryComponents.swift)
