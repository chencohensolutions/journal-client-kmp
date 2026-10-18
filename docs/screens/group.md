# Group

Photos of one moment shown as a single tile in their story: a burst of near-identical shots, or any photos you
picked. Groups nest: a group can hold other groups (**subgroups**), each one tile on its page, to any depth. Opened by
tapping a group tile on the [story page](story-page.md), or a subgroup's tile on its group's page.

## In the story

A group is one tile where its first photo would be (full width at its cover's ratio unless the story was arranged): just its **cover**
photo with the **number of photos** in a corner pill ("14"). No stack, title or time on the tile; the title and
time show on the group page, and screen readers hear "Group: <title or time>, 14 photos".

In the [viewer](viewer.md), swiping through a story, a group is **one stop**: its cover, with a **⧉ 14 · Open**
button that opens the group.

## Creating a group

1. **Suggestions** (automatic). The story page shows a card for each set of loose photos that look alike, and a
   group's page for each set of its own photos (accepting makes them a **subgroup**):
   *"4 similar photos · 2:09 PM · taken within 9 seconds · Show them as one?"* with **Group them** and **Not now**.
   The detail says seconds, minutes, a time range, or a date range, by how far apart the photos are. A small label
   above the title says why the photos were picked: **Look alike** (eye icon) or **Taken together** (clock icon;
   photos not read yet, grouped by time), and the title says "N similar photos" or "N photos in a row".
   - **By looks, at any time.** Photos with a fingerprint are compared whenever they were taken: in time order,
     each joins the first set where it looks like **more than half** the photos (so sets don't drift through chains
     of look-alikes), and sets of **2 or more** are suggested. Two photos look alike when their **dHash** (64-bit
     difference hash of a 9×8 grayscale copy) differs in at most 10 bits, when the cosine similarity of the
     platform's image embedding reaches its threshold, or when they share the camera's burst id (iOS
     `burstIdentifier`; Samsung `20261008_140901_092.jpg` names). Looks also **split** quick shots of different
     things that time alone would have joined.
   - **Zoom-aware.** A camera's zoom is a center crop, so each photo is also fingerprinted at its center zoomed in:
     embeddings at 1.4× and 2×, dHashes at nine steps from 1.15× to 3×. Two photos are compared whole to whole, and
     each one's whole frame against the other's zoomed centers (never two centers), so either can be the zoomed
     shot. Measured on test photos, this lifts a 1.8× zoom from about 0.6 to 0.9 similarity and leaves unrelated
     photos where they were.
   - **By time, until read.** Photos without a fingerprint yet (or that can't have one, like iCloud-only photos on
     iOS) go by the old rule: taken within **10 seconds** of the previous one or the same burst id, runs of **3+**.
   - Embeddings: **Vision feature print** on iOS (threshold 0.80, on the Neural Engine), **MediaPipe image
     embedder** with **MobileNet-V3 large** on Android (Apache 2.0, 10.9 MB, bundled in
     `sharedLogic/src/androidMain/assets`; on the CPU, about 10 ms a run, since the GPU isn't faster for it). Read
     from a 512 px copy so a 2× center keeps detail. **How alike** in Settings changes the threshold (see
     [settings.md](settings.md)).
   - Every photo of every story is read, one at a time in the background, the story changed last first (so a new
     story or group is ready first); cards update as fingerprints arrive. Fingerprints are cached per device and
     model in the caches folder (`similarity/fingerprints.json`), never synced: the two platforms' embeddings can't
     be compared. Read failures are logged (`PhotoSimilarity` on Android).
   - Videos are left out. A set covering every tile of a group isn't suggested on its page.
   - **Not now** is saved in story.json (`dismissedSuggestions`, by the set's first photo), so it isn't suggested
     again; a set holding any dismissed photo stays hidden.
   - Code: `findSuggestions`, `findBursts`, `BURST_GAP_MILLIS`, `BURST_MIN_PHOTOS` in
     [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt);
     fingerprints in [similarity/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/similarity/).
2. **Select, then Group.** Long-press a photo or group in the story to start selecting; tap others; **Group**
   (needs two or more tiles) makes a new group of them in their place. Selected groups become its **subgroups**. The
   bar also has **Remove** (from the story).
3. **Subgroups.** The same on a group's page: long-press its photos or subgroups, then **Group**, and they become a
   subgroup of it. Selecting every tile of the group can't be grouped (it would only wrap the group again).
4. **Merge.** When a group is among the selected tiles the bar also offers **Merge**: everything joins the selected
   group that comes first, which keeps its title, cover, layout and text; the other groups' photos and subgroups move
   into it and their text is added after its own (the first other title is taken if it had none), so no text is lost.
   The other groups go away.

After grouping or merging, *"Grouped 14 photos · Undo"* shows for a few seconds; Undo puts the story back exactly as
it was (`StoriesController.restore`).

## The group page

- The cover across the top, the story's name (and the groups around this one: "JAPAN TRIP › DAY AT THE SEA") above
  the group's title (or its time when untitled), and "Oct 8 · 2:09 – 2:10 PM · 14 photos", counting subgroups' photos.
- **Story text** in a serif, with **Edit** (full-screen editor). Empty groups prompt "Write about this moment…".
- Its photos and subgroups, oldest first, three across; a subgroup is its cover with the photo count, like a group in
  the story. The tile holding the cover is marked **Cover**. Tapping a photo opens the viewer on the group's photos
  (each subgroup one stop); tapping a subgroup opens its page.
- **Long-press** to select: **Group** (into a subgroup), **Merge** (when a subgroup is selected) and **Take out**
  (the photos move to the level above: the group around this one, or the story). Undo as in the story.
- **⋯:** **Rename** (a blank title removes it) and **Ungroup**.

In the viewer opened from a group, the details panel shows **Set as cover** and **Remove from group** instead of
the story list.

## Rules

- Within one story a photo is directly in at most one group (and so in that group's parents too); grouping photos
  that are already in another group moves them. Another story can group the same photo differently.
- A group needs two tiles (photos or subgroups). Removing photos until one tile is left, or removing them from the
  story, dissolves it.
- **Ungrouping** (or dissolving) moves the group's photos and subgroups one level up, into the group around it or
  the story, and its text to the end of that group's text or the story's opening text, so no text is lost.
- The cover is the oldest photo until you choose another, which can be any photo of the group or its subgroups; if
  the cover leaves the group, the oldest photo takes over.
- Only the story's own tiles can be arranged; a group's page, at any depth, keeps its automatic grid.

## How it's saved

In the story's folder (see [story page](story-page.md)):

- **story.json:** a `group` tile where the group's first photo would be, holding its photos as `media` tiles and
  its subgroups as `group` tiles, oldest first:

```json
{ "type": "group", "id": "g1", "title": "Fixing the car", "cover": "27",
  "tiles": [ { "type": "media", "deviceId": "27", "fileName": "20261008_140901_092.jpg", "takenAt": 1791457746123 },
             { "type": "group", "id": "g2", "title": "The engine", "cover": "29", "tiles": [ … ] }, … ] }
```

- **story.md:** the group's text under its marker, which Markdown viewers hide; each group's section is followed by
  its subgroups':

```markdown
# Walk with the dog

<!-- group:g1 -->
The car did not start, so the walk waited.
```

Group ids (`g1`, `g2`…) are unique within their story and never reused while a section of story.md still uses them.

## Code

- Shared: `PhotoSimilarity` and the dHash in
  [similarity/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/similarity/), with
  `AndroidPhotoAnalyzer` / `IosPhotoAnalyzer` in the platform source sets.
- Shared: `GroupView`, `StoryTile`, `BurstSuggestion` and the group actions in
  [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt);
  [Story.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/Story.kt)
- Android: [GroupScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/GroupScreen.kt),
  tiles, suggestion card and story editor in
  [GroupComponents.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/GroupComponents.kt)
- iOS: [GroupPageView.swift](../../iosApp/iosApp/GroupPageView.swift),
  [GroupComponents.swift](../../iosApp/iosApp/GroupComponents.swift)
