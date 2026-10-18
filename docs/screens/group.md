# Group

Photos of one moment shown as a single tile in their story: a burst of near-identical shots, or any photos you
picked. Opened by tapping a group tile on the [story page](story-page.md).

## In the story

A group is one tile where its first photo would be (full width at its cover's ratio unless the story was arranged): just its **cover**
photo with the **number of photos** in a corner pill ("14"). No stack, title or time on the tile; the title and
time show on the group page, and screen readers hear "Group: <title or time>, 14 photos".

In the [viewer](viewer.md), swiping through a story, a group is **one stop**: its cover, with a **⧉ 14 · Open**
button that opens the group.

## Creating a group

1. **Burst suggestions** (automatic). The story page shows a card for each burst among its loose photos:
   *"14 similar photos · 2:09 PM · taken within 9 seconds · Show them as one?"* with **Group them** and **Not now**.
   - Photos are taken in time order; a photo joins the current run if it was taken **within 10 seconds** of the
     previous one, or carries the **same camera burst id** (iOS `burstIdentifier`; Samsung names burst shots
     `20261008_140901_092.jpg`, the same second plus a shot number).
   - A run of **3 or more photos** is suggested. Videos and photos already in a group are left out.
   - **Not now** is saved in story.json (`dismissedSuggestions`, by the run's first photo), so it isn't suggested
     again.
   - Constants: `BURST_GAP_MILLIS` and `BURST_MIN_PHOTOS` in
     [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt).
2. **Select, then Group.** Long-press a photo or group in the story to start selecting; tap others; **Group**
   (needs two or more tiles). The bar also has **Remove** (from the story).
3. **Merge.** Select a group with photos, or two or more groups: the button reads **Merge**. Everything joins the
   selected group that comes first in the story, which keeps its title, cover, layout and text; the other groups'
   text is added after its own (and the first other title is taken if it had none), so no text is lost. The other
   groups go away.

After grouping or merging, *"Grouped 14 photos · Undo"* shows for a few seconds; Undo puts the story back exactly as
it was (`StoriesController.restore`).

## The group page

- The cover across the top, the story's name above the group's title (or its time when untitled), and
  "Oct 8 · 2:09 – 2:10 PM · 14 photos".
- **Story text** in a serif, with **Edit** (full-screen editor). Empty groups prompt "Write about this moment…".
- All its photos, oldest first, the cover marked **Cover**. Tapping one opens the viewer on the group's photos.
- **⋯:** **Rename** (a blank title removes it) and **Ungroup**.

In the viewer opened from a group, the details panel shows **Set as cover** and **Remove from group** instead of
the story list.

## Rules

- Within one story a photo is in at most one group; grouping photos that are already in another group moves them.
  Another story can group the same photo differently.
- A group needs two photos. Removing photos until one is left, or removing them from the story, dissolves it.
- **Ungrouping** (or dissolving) keeps the photos in the story and moves the group's text to the end of the story's
  opening text, so no text is lost.
- The cover is the first photo until you choose another; if the cover leaves the group, the first photo takes over.

## How it's saved

In the story's folder (see [story page](story-page.md)):

- **story.json:** a `group` tile where the group's first photo would be, holding its photos as `media` tiles:

```json
{ "type": "group", "id": "g1", "title": "Fixing the car", "cover": "27",
  "tiles": [ { "type": "media", "deviceId": "27", "fileName": "20261008_140901_092.jpg", "takenAt": 1791457746123 }, … ] }
```

- **story.md:** the group's text under its marker, which Markdown viewers hide:

```markdown
# Walk with the dog

<!-- group:g1 -->
The car did not start, so the walk waited.
```

Group ids (`g1`, `g2`…) are unique within their story and never reused while a section of story.md still uses them.

## Code

- Shared: `GroupView`, `StoryTile`, `BurstSuggestion` and the group actions in
  [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt);
  [Story.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/Story.kt)
- Android: [GroupScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/GroupScreen.kt),
  tiles, suggestion card and story editor in
  [GroupComponents.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/GroupComponents.kt)
- iOS: [GroupPageView.swift](../../iosApp/iosApp/GroupPageView.swift),
  [GroupComponents.swift](../../iosApp/iosApp/GroupComponents.swift)
