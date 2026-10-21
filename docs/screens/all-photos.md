# All photos

Every photo and video on the device in one grid, without journal structure: for scrolling and finding things.
Opened from the Journal's **⋯** menu.

## Layout

- Three columns, photos edge to edge with 2 pt gaps, newest day first.
- A day with three or more items opens with a 2×2 photo and two small ones beside it; the rest fill rows of three.
- The day's name is written over its first row, on a dark fade.
- Every tile has a backup badge, and videos have the play/length chip (see [README.md](README.md)).
- **Top bar:** back, "All photos", and the Offline pill when the server can't be reached.
- **Bottom controls:** **N not backed up**, and a filter bar: **All / Not backed up / Videos**. A filter that
  matches nothing shows "Nothing here."

## Behavior

- Tapping a tile opens the [viewer](viewer.md), which swipes through everything in the current filter.
- The filter is kept while the viewer is open and when coming back to this screen. Leaving All photos resets it
  on Android; iOS keeps it until the app restarts.
- Filtering is `LibraryState.days(filter)`, so both apps filter the same way, and then `StoriesState.shownDays`:
  only what [Settings › Show in the app](settings.md) shows, in the grid, its filters and the viewer. The **N not
  backed up** pill still counts hidden photos (hiding doesn't change backup), so it can be more than the filter shows.

## Code

- Android: [AllPhotosScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/AllPhotosScreen.kt).
  Tiles and badges are in [MediaTile.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/MediaTile.kt).
- iOS: [AllPhotosView.swift](../../iosApp/iosApp/AllPhotosView.swift). Tiles and badges are in
  [MediaTileView.swift](../../iosApp/iosApp/MediaTileView.swift).
