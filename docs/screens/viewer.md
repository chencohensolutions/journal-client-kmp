# Viewer

One photo or video at a time, full screen on black.

## What it swipes through

- Opened from a tag: the photos with that tag.
- Opened from a group: that group's photos.
- Opened from a story: that story's photos, with each group as one stop (its cover) and a **⧉ 14 · Open** button
  in the top bar that opens the group.
- Opened from a day on the Journal: that day's unsorted items.
- Opened from All photos: everything in the current filter.

The list is fixed when the viewer opens, so tagging a photo or adding it to a story (which takes it out of Unsorted)
doesn't pull it out from under you. If the open item is deleted from the device, the viewer closes.

## Layout

- **Top bar:** back, the position ("4 of 29 · Yesterday"), and the **ⓘ** button.
- **Photos:** a small version appears at once, then the full image (at most 2560 px on the longer side on Android,
  screen size on iOS).
- **Videos:** the first frame with a play button. Play starts the system player (Android `VideoView` with media
  controls, iOS `VideoPlayer`). Swiping away stops it.
- **Details panel** (ⓘ, hidden by default):
  - The date ("Friday, October 16, 2026") and time.
  - **Note:** the photo's own text (serif), shown wherever the photo appears. Tap to write or edit.
  - **Tags:** chips with ×, and **+ Tag**, which opens the same kind of search sheet for tags (recent tags before
    typing, **Create tag "…"** for a new one). See [tags.md](tags.md).
  - The backup status, with a progress bar and % while uploading.
  - **Stories:** the stories the item is in ("Car trouble, Walk with the dog", or "Not in any story"),
    **+ Add to story**, and **− Remove from <story>** when the viewer was opened from that story.
    **Add to story** opens a search sheet, focused and ready to type. Before typing it suggests stories that hold
    photos **on these days**, **nearby** ones (within 7 days) and the 3 most **recent**; typing searches every story
    by title (anywhere in the word, one typo allowed for 4+ letters), closest in time first, with
    **Create "…"** at the top. A check marks the stories the item is in; tapping one adds or removes it.
  - **Group** instead of Stories when opened from a group: its name, **Set as cover** (or "This is the cover"),
    and **− Remove from group**.
  - File name, size (Android only), dimensions, and length for videos.

## Platform notes

- **iOS** doesn't report file size cheaply, so it's not shown. The file name comes from PhotoKit when the panel
  opens. Photos stored only in iCloud are downloaded when viewed.
- **Android emulator:** 4K HEVC videos from recent phones may show no first frame and fail to play, because the
  emulator's decoder can't handle them. Real devices can.

## Code

- Android: [ViewerScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/ViewerScreen.kt)
- iOS: [MediaViewerView.swift](../../iosApp/iosApp/MediaViewerView.swift), loading in
  [PhotoLibraryImages.swift](../../iosApp/iosApp/PhotoLibraryImages.swift)
