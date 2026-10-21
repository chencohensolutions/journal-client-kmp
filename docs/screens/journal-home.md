# Journal (home)

The main screen, with three tabs (renamed on Oct 19, 2026 from Journal / Unsorted; **Explore** added Oct 20, 2026,
on iOS since Oct 21, 2026). **Stories** is the timeline of
**stories** (a trip, a birthday, a person, anything worth naming), newest first; a story sits at its newest photo's
day. **Moments** holds **every photo**, in rows: one row per day, or with the [grouping setting](settings.md) one row
per run of photos without a long break. Photos never show on the Stories tab outside their story card.

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
- **Story cards:** the title above, then "Oct 8 – 16 · 9 days · 142 photos" (an untitled story shows only that line,
  in bold, as its heading), with a cloud-off icon and count at the right while any photo isn't on the server. Under
  them a mosaic of the story's **main photos**: its first six tiles in the story's order, a group as its cover. One
  photo is full width, two sit side by side, three to five are a large one with two beside it, and six add a row of
  three under them. The last photo shows "+N" for the rest of the story. Tapping opens the [story](story-page.md).
- **Moments:** a feed of every photo that groups itself, full-width photos split by time, place, source and
  looks, with the source filter bar under the title; see [moments.md](moments.md). On iOS since Oct 21, 2026 (until
  then it showed rows of small unsorted photos with **+ Story**).
- **Bottom controls:**
  - **N not backed up**, while anything isn't on the server.
  - **Stories / Moments / Explore** switch: Stories shows only story cards, Moments the photos, Explore the photos by
    town and year and the map ([explore.md](explore.md)). No counts. While photos are picked in Moments,
    **Cancel · N selected · Merge** takes its place.

Empty, loading and error states show a single line of text in place of the days. With photos but no stories yet,
the Stories tab says "No stories yet. Make one from a day in Moments."

## Behavior

- Connects to the server when it opens; the result only shows as the Offline pill or the Register again card.
- Re-reads the library whenever the app comes to the foreground, so new photos and permission changes show up.
- Headings use the device's locale and time zone.
- Feed rows, moments and sets are keyed by their oldest photo's id, so they keep their place (and a set its page)
  when newer photos join them.

## Not built yet

These are in the design but wait for features that don't exist:

- **Sub-stories.** They will only show inside a story, with an option in Settings later.
- **Selecting several photos** to put them in a story at once.
- **Month scrubber** on the right edge for fast scrolling.

## Code

- Android: [JournalHomeScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/JournalHomeScreen.kt);
  navigation, permission handling and refresh on resume in
  [LibraryApp.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/LibraryApp.kt).
- iOS: [JournalHomeView.swift](../../iosApp/iosApp/JournalHomeView.swift), the Moments feed in
  [MomentsFeedViews.swift](../../iosApp/iosApp/MomentsFeedViews.swift) and
  [FeedVideo.swift](../../iosApp/iosApp/FeedVideo.swift); navigation and refresh in
  [LibraryRootView.swift](../../iosApp/iosApp/LibraryRootView.swift).
