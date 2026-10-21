# Viewer

One photo or video at a time, full screen on black.

## What it swipes through

- Opened from a tag: the photos with that tag.
- Opened from a group: that group's photos, with each subgroup as one stop (its cover) and the same **Open** button.
- Opened from a story: that story's photos, with each group as one stop (its cover) and a **⧉ 14 · Open** button
  in the top bar that opens the group.
- Opened from a row of the Moments tab: that row's photos as shown.
- Opened from All photos: everything in the current filter.

The list is fixed when the viewer opens, so tagging a photo or adding it to a story (which takes it out of Unsorted)
doesn't pull it out from under you. If the open item is deleted from the device, the viewer closes.

## Layout

- **Top bar:** back, the position ("4 of 29 · Yesterday"), and the **ⓘ** button.
- **Bottom:** **Write** (the note editor) and **Crop** (photos only; opens the [crop screen](crop.md) on
  Portrait or Landscape, as the phone is held). Hidden while the details panel is open.
- **Crops:** a photo with a portrait (upright) or landscape (sideways) crop fills the screen at it, with
  **Cropped · Show all** at the top.
- **Photos:** a small version appears at once, then the full image (at most 2560 px on the longer side on Android,
  screen size on iOS).
- **Videos:** the first frame with a play button. Play starts the system player (Android `VideoView` with media
  controls, iOS `VideoPlayer`). Swiping away stops it.
- **Details panel** (ⓘ, hidden by default):
  - The date ("Friday, October 16, 2026") and time.
  - **Note:** the photo's own text (serif), shown wherever the photo appears. Tap to write or edit.
  - **Tags:** chips with ×, and **+ Tag**, which opens the same kind of search sheet for tags (recent tags before
    typing, **Create tag "…"** for a new one). See [tags.md](tags.md).
  - **Stories:** the stories the item is in ("Car trouble, Walk with the dog", or "Not in any story"),
    **+ Add to story**, and **− Remove from <story>** when the viewer was opened from that story.
    **Add to story** opens a search sheet, focused and ready to type. Before typing it suggests stories that hold
    photos **on these days**, **nearby** ones (within 7 days) and the 3 most **recent**; typing searches every story
    by title (anywhere in the word, one typo allowed for 4+ letters), closest in time first, with
    **Create "…"** at the top. A check marks the stories the item is in; tapping one adds or removes it.
  - **Group** instead of Stories when opened from a group: its name, **Set as cover** (or "This is the cover"),
    and **− Remove from group** (the photo moves to the level above the group, even from a subgroup).
  - **The original** (Android; iOS shows the backup status and file name, size, dimensions, length): where it is
    ("On this phone and on your server", "Only on this phone", "Downloaded to this phone, and on your server", "Only
    on your server", or the upload state with % and a bar), then file name, size, dimensions, length for videos,
    type (MIME), where it came from (Camera, WhatsApp, …) and **Location** (place name and coordinates). For a photo
    only on the server: **Preview on this phone** (the largest kept, "2560 × 1920 · 635 kB", or "None yet").
  - **Download original** (only on the server): downloads it, checks its sha256, and keeps it in the app's storage
    (`files/originals`), outside the 10 GB preview cache, so it is never trimmed. While it is here nothing is fetched
    for the photo; it shows and plays from that file.
  - **Delete from this phone** (original here and on the server), after a confirmation: first the server is asked
    again whether it has the photo, and its previews (2560 and 640 px) are kept here; without both nothing is
    removed ("Not deleted: your server didn't confirm…"). A downloaded original is then deleted; a gallery photo
    goes to the phone's trash through Android's own prompt (`PhotoRemoval.kt`, the one exception in
    `PhotoSafetyTest`). The photo keeps its place, its stories and note (it keeps its device id), and shows from its
    preview.

## Platform notes

- **iOS** doesn't report file size cheaply, so it's not shown. The file name comes from PhotoKit when the panel
  opens. Photos stored only in iCloud are downloaded when viewed. A cropped photo is loaded large enough for its
  crop to stay sharp (the screen size divided by the crop's), and the crop fills the whole screen, under the bars.
- **Android emulator:** 4K HEVC videos from recent phones may show no first frame and fail to play, because the
  emulator's decoder can't handle them. Real devices can.

## Code

- Android: [ViewerScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/ViewerScreen.kt)
- iOS: [MediaViewerView.swift](../../iosApp/iosApp/MediaViewerView.swift), loading in
  [PhotoLibraryImages.swift](../../iosApp/iosApp/PhotoLibraryImages.swift)
