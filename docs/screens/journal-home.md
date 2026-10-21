# Journal (home)

The main screen, with three tabs (renamed on Oct 19, 2026 from Journal / Unsorted; **Explore** added Oct 20, 2026,
on iOS since Oct 21, 2026). **Storyline** (it replaced the Stories tab on Oct 21, 2026, owner's decision; the
stories themselves were deleted) holds every photo you wrote about. **Moments** holds **every photo**, in rows: one
row per day, or with the [grouping setting](settings.md) one row per run of photos without a long break.

## Layout

Photos fill the screen on the page background (light or dark with the system); the controls float on top.

- **Top bar:** "Journal", the **Offline** pill (only when the server can't be reached), a **Tags** button
  ([tags.md](tags.md)) and a **⋯** menu with **All photos** and **Settings** ([settings.md](settings.md)).
- **Cards above the timeline**, when they apply:
  - **This device isn't registered with the server · Register again**: the server answered 401, or there is no
    identity. Opens the enrollment wizard.
  - **Journal can't see your photos · Allow access**: access was denied or skipped. See
    [photo-access.md](photo-access.md).
  - **Journal can see N photos and videos · Change**: limited access. Opens the system picker to change the
    selection.
- **Storyline:** every photo or video with a title or a paragraph (see the [note editor](viewer.md)), one each and
  never a set, newest first by the date taken. A small day heading comes before each new day; then the title above
  the photo, the photo full width with its Moments crop, and the paragraph under it (4 lines, then **more**), as in
  Moments. It shows photos of every source, also those Show in the app hides. Writing about a photo puts it here; to
  have one photo of a set here, open that photo and write on it. Tapping opens the [viewer](viewer.md), swiping
  through the storyline; a long press opens the photo's menu, with **Remove from storyline** (the writing stays and
  still shows in Moments; `"storyline": false` in its note file) or, once removed, **Add to storyline**.
- **Moments:** a feed of every photo that groups itself, full-width photos split by time, place, source and
  looks, with the source filter bar under the title; see [moments.md](moments.md). On iOS since Oct 21, 2026 (until
  then it showed rows of small unsorted photos with **+ Story**).
- **Bottom controls:**
  - **N not backed up**, while anything isn't on the server.
  - **Storyline / Moments / Explore** switch: Storyline shows the photos you wrote about, Moments every photo,
    Explore the photos by town and year and the map ([explore.md](explore.md)). No counts. While photos are picked in Moments,
    **Cancel · N selected · Merge** takes its place.

Empty, loading and error states show a single line of text in place of the days. With nothing written yet, the
Storyline tab says "Nothing here yet. Open a photo and tap Write: what you write shows here."

## Behavior

- Connects to the server when it opens; the result only shows as the Offline pill or the Register again card.
- Re-reads the library whenever the app comes to the foreground, so new photos and permission changes show up.
- Headings use the device's locale and time zone.
- Feed rows, moments and sets are keyed by their oldest photo's id, so they keep their place (and a set its page)
  when newer photos join them.

## Not built yet

These are in the design but wait for features that don't exist:

- **More photos with one entry** of the storyline (the owner will decide how, later); for now each entry is one
  photo.
- **Month scrubber** on the right edge for fast scrolling.

## Code

- Android: [JournalHomeScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/JournalHomeScreen.kt);
  navigation, permission handling and refresh on resume in
  [LibraryApp.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/LibraryApp.kt).
- iOS: [JournalHomeView.swift](../../iosApp/iosApp/JournalHomeView.swift), the Moments feed in
  [MomentsFeedViews.swift](../../iosApp/iosApp/MomentsFeedViews.swift) and
  [FeedVideo.swift](../../iosApp/iosApp/FeedVideo.swift); navigation and refresh in
  [LibraryRootView.swift](../../iosApp/iosApp/LibraryRootView.swift).
